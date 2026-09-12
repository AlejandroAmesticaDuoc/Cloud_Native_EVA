import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';

export function notificationHarness({ dockerCommand, launch, ready, stop, waitFor }) {
  const containers = [];
  const password = randomBytes(24).toString('hex');
  const username = 'notify-smoke';
  let rabbit;
  let mailpit;
  let notify;
  let management;
  let inbox;
  let notifyUrl;
  let environment;
  let assertions = 0;

  async function container(args) {
    const id = await dockerCommand(['run', '-d', '--label', 'pedidos360.test=true', ...args]);
    assert.match(id, /^[a-f0-9]{64}$/);
    containers.push(id);
    return id;
  }

  async function port(id, internal) {
    const binding = await dockerCommand(['port', id, `${internal}/tcp`]);
    const value = Number(binding.match(/:(\d+)\s*$/)?.[1]);
    assert.ok(value > 0);
    return value;
  }

  async function rabbitApi(path, method = 'GET', body) {
    const response = await fetch(management + path, {
      method, headers: { Authorization: 'Basic ' + Buffer.from(`${username}:${password}`).toString('base64'),
        'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(5000)
    });
    assert.ok(response.ok, `RabbitMQ API ${method} ${path}: ${response.status}`);
    const result = await response.text();
    return result ? JSON.parse(result) : null;
  }

  async function messages() {
    const response = await fetch(inbox + '/api/v1/messages?limit=500', { signal: AbortSignal.timeout(3000) });
    assert.ok(response.ok);
    return (await response.json()).messages;
  }

  async function publish(payload) {
    const response = await rabbitApi('/api/exchanges/pedidos360/amq.default/publish', 'POST', {
      properties: { content_type: 'application/json', delivery_mode: 2, message_id: randomUUID() },
      routing_key: 'q.cmd.email', payload: JSON.stringify(payload), payload_encoding: 'string'
    });
    assert.equal(response.routed, true);
  }

  async function deadLetters() {
    return await rabbitApi('/api/queues/pedidos360/q.cmd.email.dlq/get', 'POST', {
      count: 10, ackmode: 'ack_requeue_false', encoding: 'auto', truncate: 8192
    });
  }

  async function start() {
    console.log('Preparando RabbitMQ, Mailpit y Notify temporales...');
    rabbit = await container(['--name', `pedidos360-rabbit-test-${randomUUID()}`,
      '-p', '127.0.0.1::5672', '-p', '127.0.0.1::15672',
      '-e', `RABBITMQ_DEFAULT_USER=${username}`, '-e', 'RABBITMQ_DEFAULT_PASS',
      '-e', 'RABBITMQ_DEFAULT_VHOST=pedidos360', 'rabbitmq:4.3.5-management-alpine']);
    mailpit = await container(['--name', `pedidos360-mail-test-${randomUUID()}`,
      '-p', '127.0.0.1::1025', '-p', '127.0.0.1::8025', 'axllent/mailpit:v1.30.6']);
    management = `http://127.0.0.1:${await port(rabbit, 15672)}`;
    inbox = `http://127.0.0.1:${await port(mailpit, 8025)}`;
    await waitFor(async () => {
      try { return Boolean(await rabbitApi('/api/overview')); } catch { return false; }
    }, 'RabbitMQ');
    environment = {
      RABBITMQ_HOST: '127.0.0.1', RABBITMQ_PORT: String(await port(rabbit, 5672)),
      RABBITMQ_USERNAME: username, RABBITMQ_PASSWORD: password, RABBITMQ_VHOST: 'pedidos360',
      RABBITMQ_EMAIL_QUEUE: 'q.cmd.email', RABBITMQ_EMAIL_DLQ: 'q.cmd.email.dlq',
      SMTP_HOST: '127.0.0.1', SMTP_PORT: String(await port(mailpit, 1025)),
      SMTP_AUTH: 'false', SMTP_STARTTLS: 'false', SMTP_USERNAME: '', SMTP_PASSWORD: '',
      NOTIFY_EMAIL_FROM: 'no-reply@pedidos360.test', NOTIFY_EMAIL_RECIPIENT: 'demo@pedidos360.test',
      ORDERS_NOTIFICATIONS_ENABLED: 'true', ORDERS_NOTIFICATIONS_POLL_DELAY: '100'
    };
    notify = launch('ms-pedidos360-notify', environment);
    notifyUrl = await ready(notify, 'Notify');
    assertions++;
    return environment;
  }

  async function verifyFlow(sql, createAndCancel, restartOrders) {
    console.log('Comprobando correos, recuperación del broker y cola de fallidos...');
    await waitFor(async () => await sql('orders',
      'SELECT COUNT(*) FROM notification_outbox WHERE published_at IS NULL') === '0', 'publicación de avisos');
    const count = Number(await sql('orders', 'SELECT COUNT(*) FROM notification_outbox'));
    await waitFor(async () => (await messages()).length === count, 'recepción SMTP');
    const delivered = await messages();
    const completed = delivered.find(mail => mail.Subject.includes('ENTREGADO'));
    assert.ok(completed);
    const response = await fetch(`${inbox}/api/v1/message/${completed.ID}`);
    const detail = await response.json();
    assert.ok(detail.Text.includes('Seguimiento: integration-catalog-001'));
    assert.equal(detail.To[0].Address, 'demo@pedidos360.test');
    assert.equal(detail.Text.includes('Bearer'), false);
    const duplicate = await sql('orders', "SELECT COUNT(*) FROM (SELECT order_id, payload::jsonb->>'status' FROM notification_outbox GROUP BY order_id, payload::jsonb->>'status' HAVING COUNT(*) > 1) duplicates");
    assert.equal(duplicate, '0');
    assertions += 6;

    await dockerCommand(['exec', rabbit, 'rabbitmqctl', 'stop_app']);
    await createAndCancel();
    await waitFor(async () => Number(await sql('orders',
      'SELECT COUNT(*) FROM notification_outbox WHERE published_at IS NULL AND attempts > 0')) > 0,
      'persistencia del aviso con RabbitMQ caído');
    await restartOrders();
    assert.equal(await sql('orders', 'SELECT COUNT(*) FROM notification_outbox WHERE published_at IS NULL'), '2');
    await dockerCommand(['exec', rabbit, 'rabbitmqctl', 'start_app']);
    await waitFor(async () => await sql('orders',
      'SELECT COUNT(*) FROM notification_outbox WHERE published_at IS NULL') === '0', 'recuperación del outbox');
    await waitFor(async () => (await messages()).length === count + 2, 'avisos después de reinicio');
    assertions += 3;

    await publish({ schemaVersion: 99, invalid: true });
    const invalid = await waitFor(async () => {
      const batch = await deadLetters();
      return batch.length ? batch : false;
    }, 'mensaje inválido en DLQ');
    assert.equal(JSON.parse(invalid[0].payload).schemaVersion, 99);
    assert.equal(invalid[0].properties.headers['x-death'][0].reason, 'rejected');
    assert.equal((await messages()).length, count + 2);
    assertions += 3;

    const failed = { schemaVersion: 1, eventId: randomUUID(), orderId: 999999,
      customerId: 'smtp-test', status: 'CANCELADO', occurredAt: new Date().toISOString(), traceId: 'smtp-failure-test' };
    await dockerCommand(['pause', mailpit]);
    let dead;
    try {
      await publish(failed);
      dead = await waitFor(async () => {
        const batch = await deadLetters();
        return batch.length ? batch : false;
      }, 'SMTP agotó reintentos y pasó a DLQ');
    } finally { await dockerCommand(['unpause', mailpit]); }
    assert.equal(JSON.parse(dead[0].payload).eventId, failed.eventId);
    assert.equal(dead[0].properties.headers['x-death'][0].reason, 'rejected');
    await publish(JSON.parse(dead[0].payload));
    await waitFor(async () => (await messages()).length === count + 3, 'reenvío manual después de recuperar SMTP');
    assert.equal((await deadLetters()).length, 0);
    const finalHealth = await fetch(notifyUrl + '/actuator/health/readiness');
    assert.equal(finalHealth.status, 200);
    assertions += 4;
    console.log(`SUCCESS: ${assertions} comprobaciones RabbitMQ -> Notify -> SMTP.`);
  }

  async function cleanUp() {
    await stop(notify);
    for (const id of containers.reverse()) {
      if (/^[a-f0-9]{64}$/.test(id)) await dockerCommand(['rm', '-f', '-v', id]);
    }
  }

  return { start, verifyFlow, cleanUp, brokerEnvironment: { RABBITMQ_DEFAULT_PASS: password } };
}
