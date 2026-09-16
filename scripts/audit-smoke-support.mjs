import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

export function auditHarness({ dockerCommand, launch, ready, stop, waitFor, kafka }) {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const credentials = { POSTGRES_PASSWORD: randomBytes(24).toString('hex'), AUDIT_DB_PASSWORD: randomBytes(24).toString('hex') };
  let database;
  let application;
  let variables;
  let url;
  let checks = 0;

  async function sql(statement) {
    return dockerCommand(['exec', '-e', 'PGPASSWORD', database, 'psql', '-h', '127.0.0.1',
      '-U', 'pedidos360_audit', '-d', 'pedidos360_audit', '-Atqc', statement],
      { PGPASSWORD: credentials.AUDIT_DB_PASSWORD });
  }

  async function start(kafkaEnvironment) {
    console.log('Preparando Audit y su PostgreSQL temporal...');
    database = await dockerCommand(['run', '-d', '--rm', '--name', 'pedidos360-audit-test-' + randomUUID(),
      '--label', 'pedidos360.test=true', '-p', '127.0.0.1::5432', '-e', 'POSTGRES_PASSWORD', '-e', 'AUDIT_DB_PASSWORD',
      '--mount', 'type=bind,source=' + join(root, 'infra', 'postgres', '003-create-audit.sh')
        + ',target=/docker-entrypoint-initdb.d/003-create-audit.sh,readonly', 'postgres:17.11-alpine'], credentials);
    assert.match(database, /^[a-f0-9]{64}$/);
    const port = Number((await dockerCommand(['port', database, '5432/tcp'])).match(/:(\d+)\s*$/)?.[1]);
    assert.ok(port > 0);
    await waitFor(async () => { try { return await sql('SELECT 1') === '1'; } catch { return false; } }, 'base de Audit');
    variables = { ...kafkaEnvironment, AUDIT_EVENTS_ENABLED: 'true',
      AUDIT_KAFKA_GROUP: 'pedidos360-audit-smoke', AUDIT_DB_PASSWORD: credentials.AUDIT_DB_PASSWORD,
      AUDIT_DB_URL: 'jdbc:postgresql://127.0.0.1:' + port + '/pedidos360_audit' };
    application = launch('ms-pedidos360-audit', variables);
    url = await ready(application, 'Audit');
    return { AUDIT_SERVICE_URL: url };
  }

  async function verifyFlow({ sql: ordersSql, createAndCancel, request, token, directRequest, reconnectBff }) {
    console.log('Comprobando historial, permisos, duplicados y recuperación de Audit...');
    const path = '/api/v1/audit';
    const admin = token(['ADMIN']);
    const auditor = token(['AUDITOR']);
    async function drained() {
      const expected = await ordersSql('orders', 'SELECT COUNT(*) FROM order_event_outbox');
      try {
        await waitFor(async () => await sql('SELECT COUNT(*) FROM audit_events') === expected, 'consumo de Audit', 120000);
      } catch (error) {
        throw new Error(error.message + '\n' + application.output.slice(-5000));
      }
      return Number(expected);
    }
    const initial = await drained();
    const stored = JSON.parse(await ordersSql('orders',
      "SELECT json_agg(payload::json ORDER BY id) FROM order_event_outbox"));
    const page = await request('GET', path + '?size=100', 200, auditor);
    assert.equal(page.items.length, initial);
    for (const event of stored) assert.deepEqual(page.items.find(row => row.event.eventId === event.eventId).event, event);
    checks += 2;
    await request('GET', path, 200, admin);
    await request('GET', path, 401);
    await directRequest(url, 'GET', path, 401);
    for (const role of ['CLIENTE', 'OPERADOR']) {
      await request('GET', path, 403, token([role]));
      await directRequest(url, 'GET', path, 403, token([role]));
    }
    await request('GET', path, 403, token(['ADMIN'], { scp: undefined }));
    await directRequest(url, 'GET', path, 403, token(['ADMIN'], { scp: undefined }));
    await request('GET', path + '?size=101', 400, auditor);
    await request('POST', path, 403, admin, {});
    const first = await request('GET', path + '?size=1', 200, auditor);
    const second = await request('GET', path + '?size=1&afterId=' + first.nextAfterId, 200, auditor);
    assert.ok(second.items[0].id > first.items[0].id);
    assert.notEqual(first.items[0].event.eventId, second.items[0].event.eventId);
    const orderId = stored[0].orderId;
    const history = await request('GET', path + '/orders/' + orderId, 200, auditor);
    assert.deepEqual(history.items.map(row => row.event), stored.filter(event => event.orderId === orderId));
    const empty = await request('GET', path + '/orders/99999999', 200, auditor);
    assert.deepEqual(empty.items, []);
    checks += 4;
    await stop(application);
    application = launch('ms-pedidos360-audit', variables);
    url = await ready(application, 'Audit reiniciado');
    await reconnectBff(url);
    assert.equal((await request('GET', path + '?size=100', 200, auditor)).items.length, initial);
    const replay = stored[0];
    await kafka.publish(String(replay.orderId), JSON.stringify(replay));
    await kafka.publish(String(replay.orderId), '{"invalid":true}');
    await waitFor(async () => await sql('SELECT COUNT(*) FROM audit_rejections') === '1', 'rechazo de mensaje inválido');
    assert.equal(Number(await sql('SELECT COUNT(*) FROM audit_events')), initial);
    await kafka.publish(String(replay.orderId), JSON.stringify({ ...replay, total: replay.total + 1 }));
    await waitFor(async () => await sql('SELECT COUNT(*) FROM audit_rejections') === '2', 'conflicto de eventId');
    assert.equal(Number(await sql('SELECT COUNT(*) FROM audit_events')), initial);
    checks += 4;
    await dockerCommand(['pause', database]);
    try {
      await createAndCancel();
      await delay(25000);
      const readiness = await fetch(url + '/actuator/health/readiness', { signal: AbortSignal.timeout(10000) });
      assert.equal(readiness.status, 503);
      assert.match(application.output, /Connection is not available|CannotCreateTransaction|CannotGetJdbcConnection|SQLTransientConnectionException/);
      checks += 2;
    } finally { await dockerCommand(['unpause', database]); }
    assert.equal(await drained(), initial + 2);
    await directRequest(url, 'GET', '/actuator/health/readiness', 200);
    const recovered = await request('GET', path + '?size=100', 200, auditor);
    assert.equal(recovered.items.length, initial + 2);
    checks += 2;
    await stop(application);
    await request('GET', path, 502, auditor);
    console.log('SUCCESS: ' + checks + ' comprobaciones de persistencia y recuperación de Audit; permisos HTTP incluidos en el total general.');
  }

  async function cleanUp() {
    await stop(application);
    if (database && /^[a-f0-9]{64}$/.test(database)) await dockerCommand(['rm', '-f', '-v', database]);
  }

  return { start, verifyFlow, cleanUp };
}
