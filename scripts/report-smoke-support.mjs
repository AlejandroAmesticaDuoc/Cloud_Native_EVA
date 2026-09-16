import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

export function reportHarness({ dockerCommand, launch, ready, stop, waitFor, kafka }) {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const credentials = { POSTGRES_PASSWORD: randomBytes(24).toString('hex'), REPORT_DB_PASSWORD: randomBytes(24).toString('hex') };
  let database;
  let application;
  let variables;
  let url;
  let checks = 0;

  async function sql(statement) {
    return dockerCommand(['exec', '-e', 'PGPASSWORD', database, 'psql', '-h', '127.0.0.1',
      '-U', 'pedidos360_report', '-d', 'pedidos360_report', '-Atqc', statement],
      { PGPASSWORD: credentials.REPORT_DB_PASSWORD });
  }

  async function start(kafkaEnvironment) {
    console.log('Preparando Report y su PostgreSQL temporal...');
    database = await dockerCommand(['run', '-d', '--rm', '--name', 'pedidos360-report-test-' + randomUUID(),
      '--label', 'pedidos360.test=true', '-p', '127.0.0.1::5432', '-e', 'POSTGRES_PASSWORD', '-e', 'REPORT_DB_PASSWORD',
      '--mount', 'type=bind,source=' + join(root, 'infra', 'postgres', '004-create-report.sh')
        + ',target=/docker-entrypoint-initdb.d/004-create-report.sh,readonly', 'postgres:17.11-alpine'], credentials);
    assert.match(database, /^[a-f0-9]{64}$/);
    const port = Number((await dockerCommand(['port', database, '5432/tcp'])).match(/:(\d+)\s*$/)?.[1]);
    assert.ok(port > 0);
    await waitFor(async () => { try { return await sql('SELECT 1') === '1'; } catch { return false; } }, 'base de Report');
    variables = { ...kafkaEnvironment, REPORT_EVENTS_ENABLED: 'true', REPORT_KAFKA_GROUP: 'pedidos360-report-smoke',
      REPORT_DB_USERNAME: 'pedidos360_report', REPORT_DB_PASSWORD: credentials.REPORT_DB_PASSWORD,
      REPORT_DB_URL: 'jdbc:postgresql://127.0.0.1:' + port + '/pedidos360_report' };
    application = launch('ms-pedidos360-report', variables);
    url = await ready(application, 'Report');
    return { REPORT_SERVICE_URL: url };
  }

  async function consumed(expected) {
    try {
      await waitFor(async () => Number(await sql('SELECT COUNT(*) FROM report_events')) === expected,
        'consumo de Report', 120000);
    } catch (error) { throw new Error(error.message + '\n' + application.output.slice(-5000)); }
  }

  async function verifyFlow({ sql: ordersSql, createAndCancel, request, token, directRequest, reconnectBff }) {
    console.log('Comprobando KPIs reales, grupo independiente de Kafka y permisos de Report...');
    const admin = token(['ADMIN']);
    const summaryPath = '/api/v1/reports/summary';
    const leadPath = '/api/v1/reports/lead-time';
    const orderEvents = async () => JSON.parse(await ordersSql('orders',
      "SELECT COALESCE(json_agg(payload::json ORDER BY id), '[]'::json) FROM order_event_outbox"));
    const events = await orderEvents();
    await consumed(events.length);
    await waitFor(async () => Number(await sql('SELECT COUNT(*) FROM report_rejections')) === 2,
      'Report recibe también los mensajes inválidos de la prueba de Audit');
    checks += 2;

    async function verifyKpis(expected) {
      const latest = new Map();
      for (const event of expected) {
        if (!latest.has(event.orderId) || latest.get(event.orderId).aggregateVersion < event.aggregateVersion) {
          latest.set(event.orderId, event);
        }
      }
      const summary = await request('GET', summaryPath, 200, admin);
      const lead = await request('GET', leadPath, 200, admin);
      const byStatus = Object.fromEntries(['CREADO', 'ACEPTADO', 'EN_PREPARACION', 'DESPACHADO', 'ENTREGADO', 'CANCELADO']
        .map(status => [status, [...latest.values()].filter(event => event.status === status).length]));
      const delivered = [...latest.values()].filter(event => event.status === 'ENTREGADO');
      const cents = rows => rows.reduce((sum, event) => sum + Math.round(event.total * 100), 0) / 100;
      assert.equal(summary.totalOrders, latest.size);
      assert.deepEqual(summary.ordersByStatus, byStatus);
      assert.equal(summary.deliveredOrders, delivered.length);
      assert.equal(summary.cancelledOrders, byStatus.CANCELADO);
      assert.equal(summary.activeOrders, latest.size - byStatus.ENTREGADO - byStatus.CANCELADO);
      assert.equal(summary.deliveredAmount, cents(delivered));
      assert.equal(summary.salesByHour.length, 24);
      const from = Date.parse(summary.hourlyFrom);
      for (let i = 0; i < 24; i++) {
        const hour = from + i * 3600000;
        const rows = delivered.filter(event => Date.parse(event.occurredAt) >= hour && Date.parse(event.occurredAt) < hour + 3600000);
        assert.equal(Date.parse(summary.salesByHour[i].hour), hour);
        assert.equal(summary.salesByHour[i].deliveredOrders, rows.length);
        assert.equal(summary.salesByHour[i].deliveredAmount, cents(rows));
      }
      assert.equal(Date.parse(summary.hourlyTo), from + 24 * 3600000);
      assert.equal(lead.deliveredOrders, delivered.length);
      if (delivered.length) {
        const seconds = delivered.map(event => (Date.parse(event.occurredAt) - Date.parse(event.createdAt)) / 1000);
        const average = seconds.reduce((a, b) => a + b, 0) / seconds.length;
        assert.ok(Math.abs(lead.averageSeconds - average) <= 0.002);
        assert.ok(Math.abs(lead.minimumSeconds - Math.min(...seconds)) <= 0.002);
        assert.ok(Math.abs(lead.maximumSeconds - Math.max(...seconds)) <= 0.002);
      } else {
        assert.equal(lead.averageSeconds, null);
        assert.equal(lead.minimumSeconds, null);
        assert.equal(lead.maximumSeconds, null);
      }
      checks += 8;
    }

    await verifyKpis(events);
    for (const path of [summaryPath, leadPath]) {
      await request('GET', path, 401);
      await directRequest(url, 'GET', path, 401);
      for (const role of ['CLIENTE', 'OPERADOR', 'AUDITOR']) {
        await request('GET', path, 403, token([role]));
        await directRequest(url, 'GET', path, 403, token([role]));
      }
      await request('GET', path, 403, token(['ADMIN'], { scp: undefined }));
      await directRequest(url, 'GET', path, 403, token(['ADMIN'], { scp: undefined }));
      await request('POST', path, 403, admin, {});
    }
    await request('GET', summaryPath + '?hours=169', 400, admin);
    assert.equal((await request('GET', summaryPath + '?hours=2', 200, admin)).salesByHour.length, 2);
    await directRequest(url, 'GET', summaryPath, 200, admin);
    checks++;

    const replay = events[0];
    await kafka.publish(String(replay.orderId), JSON.stringify(replay));
    await kafka.publish(String(replay.orderId), '{"invalid":true}');
    await kafka.publish(String(replay.orderId), JSON.stringify({ ...replay, total: replay.total + 1 }));
    await waitFor(async () => Number(await sql('SELECT COUNT(*) FROM report_rejections')) === 4, 'rechazos de Report');
    await consumed(events.length);
    checks += 2;

    const deliveredAt = new Date(Math.floor(Date.now() / 3600000) * 3600000 + 60000).toISOString();
    const createdAt = new Date(Date.parse(deliveredAt) - 1800000).toISOString();
    const delivery = { schemaVersion: 1, eventId: randomUUID(), eventType: 'OrderStatusChanged', orderId: 900000001,
      aggregateVersion: 5, occurredAt: deliveredAt, traceId: 'report-late-test', actorId: 'operator',
      customerId: 'report-customer', previousStatus: 'DESPACHADO', status: 'ENTREGADO', createdAt, total: 12.34 };
    const creation = { ...delivery, eventId: randomUUID(), eventType: 'OrderCreated', aggregateVersion: 1,
      occurredAt: createdAt, previousStatus: null, status: 'CREADO' };
    await kafka.publish(String(delivery.orderId), JSON.stringify(delivery));
    await kafka.publish(String(creation.orderId), JSON.stringify(creation));
    await consumed(events.length + 2);
    assert.equal(await sql('SELECT aggregate_version FROM report_orders WHERE order_id = 900000001'), '5');
    await verifyKpis([...events, delivery, creation]);
    checks++;

    console.log('Comprobando reinicio de Report y recuperación con su base sin responder...');
    await stop(application);
    application = launch('ms-pedidos360-report', variables);
    url = await ready(application, 'Report reiniciado');
    await reconnectBff(url);
    await kafka.publish(String(delivery.orderId), JSON.stringify(delivery));
    await kafka.publish(String(delivery.orderId), '{"invalid":true}');
    await waitFor(async () => Number(await sql('SELECT COUNT(*) FROM report_rejections')) === 5, 'duplicados tras reinicio');
    await consumed(events.length + 2);
    checks++;

    await dockerCommand(['pause', database]);
    try {
      await createAndCancel();
      await delay(25000);
      const readiness = await fetch(url + '/actuator/health/readiness', { signal: AbortSignal.timeout(10000) });
      assert.equal(readiness.status, 503);
      assert.match(application.output, /Connection is not available|CannotCreateTransaction|CannotGetJdbcConnection|SQLTransientConnectionException/);
      checks += 2;
    } finally { await dockerCommand(['unpause', database]); }
    const recovered = await orderEvents();
    await consumed(recovered.length + 2);
    await verifyKpis([...recovered, delivery, creation]);
    await directRequest(url, 'GET', '/actuator/health/readiness', 200);
    await stop(application);
    await request('GET', summaryPath, 502, admin);
    await request('GET', leadPath, 502, admin);
    console.log('SUCCESS: ' + checks + ' comprobaciones de KPIs y recuperación de Report; permisos HTTP incluidos en el total general.');
  }

  async function cleanUp() {
    await stop(application);
    if (database && /^[a-f0-9]{64}$/.test(database)) await dockerCommand(['rm', '-f', '-v', database]);
  }

  return { start, verifyFlow, cleanUp };
}
