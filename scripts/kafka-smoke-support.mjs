import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { createServer } from 'node:net';

export function kafkaHarness({ dockerCommand, dockerInput, waitFor }) {
  let container;
  let assertions = 0;

  async function freePort() {
    const probe = createServer();
    await new Promise((resolve, reject) => { probe.once('error', reject); probe.listen(0, '127.0.0.1', resolve); });
    const port = probe.address().port;
    await new Promise(resolve => probe.close(resolve));
    return port;
  }

  async function cli(command, args = []) {
    return dockerCommand(['exec', container, `/opt/kafka/bin/${command}.sh`,
      '--bootstrap-server', 'localhost:19092', ...args]);
  }

  async function start() {
    console.log('Preparando Kafka KRaft temporal...');
    const port = await freePort();
    const variables = {
      KAFKA_NODE_ID: '1', KAFKA_PROCESS_ROLES: 'broker,controller',
      KAFKA_LISTENERS: 'INTERNAL://:19092,EXTERNAL://:9092,CONTROLLER://:29093',
      KAFKA_ADVERTISED_LISTENERS: `INTERNAL://localhost:19092,EXTERNAL://127.0.0.1:${port}`,
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: 'INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT',
      KAFKA_INTER_BROKER_LISTENER_NAME: 'INTERNAL', KAFKA_CONTROLLER_LISTENER_NAMES: 'CONTROLLER',
      KAFKA_CONTROLLER_QUORUM_VOTERS: '1@localhost:29093', CLUSTER_ID: randomBytes(16).toString('base64url'),
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: '1', KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: '1',
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: '1', KAFKA_SHARE_COORDINATOR_STATE_TOPIC_REPLICATION_FACTOR: '1',
      KAFKA_SHARE_COORDINATOR_STATE_TOPIC_MIN_ISR: '1', KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: '0',
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: 'false', KAFKA_LOG_DIRS: '/var/lib/kafka/data',
      KAFKA_HEAP_OPTS: '-Xms256m -Xmx512m'
    };
    container = await dockerCommand(['run', '-d', '--name', `pedidos360-kafka-test-${randomUUID()}`,
      '--label', 'pedidos360.test=true', '-p', `127.0.0.1:${port}:9092`, '-v', '/var/lib/kafka/data',
      ...Object.entries(variables).flatMap(([key, value]) => ['-e', `${key}=${value}`]), 'apache/kafka:4.3.1']);
    assert.match(container, /^[a-f0-9]{64}$/);
    await waitFor(async () => {
      try { await cli('kafka-topics', ['--list']); return true; } catch { return false; }
    }, 'inicio de Kafka', 120000);
    await cli('kafka-topics', ['--create', '--if-not-exists', '--topic', 'orders.events',
      '--partitions', '3', '--replication-factor', '1', '--config', 'retention.ms=604800000',
      '--config', 'cleanup.policy=delete']);
    assertions++;
    return { ORDERS_EVENTS_ENABLED: 'true', ORDERS_EVENTS_POLL_DELAY: '100',
      KAFKA_BOOTSTRAP_SERVERS: `127.0.0.1:${port}`, KAFKA_ORDERS_TOPIC: 'orders.events' };
  }

  async function readEvents() {
    const offsets = await cli('kafka-get-offsets', ['--topic', 'orders.events', '--time', '-1']);
    const count = offsets.split(/\r?\n/).filter(line => line.startsWith('orders.events:'))
      .reduce((sum, line) => sum + Number(line.split(':')[2]), 0);
    assert.ok(count > 0);
    const output = await cli('kafka-console-consumer', ['--topic', 'orders.events', '--from-beginning',
      '--max-messages', String(count), '--timeout-ms', '15000',
      '--property', 'print.key=true', '--property', 'print.partition=true']);
    const records = output.split(/\r?\n/).filter(line => line.startsWith('Partition:')).map(line => {
      const match = line.match(/^Partition:(\d+)\t(\d+)\t(\{.*\})$/);
      assert.ok(match, 'Formato inesperado en el consumidor de prueba');
      return { partition: Number(match[1]), key: match[2], event: JSON.parse(match[3]) };
    });
    assert.equal(records.length, count);
    return records;
  }

  async function verifyRecords(sql) {
    await waitFor(async () => await sql('orders',
      'SELECT COUNT(*) FROM order_event_outbox WHERE published_at IS NULL') === '0', 'publicación Kafka', 120000);
    const stored = JSON.parse(await sql('orders',
      "SELECT COALESCE(json_agg(payload::json ORDER BY id), '[]'::json) FROM order_event_outbox"));
    const records = await readEvents();
    const unique = new Map(records.map(record => [record.event.eventId, record.event]));
    assert.equal(unique.size, stored.length);
    for (const event of stored) assert.deepEqual(unique.get(event.eventId), event);
    assert.deepEqual([...new Set(stored.map(event => event.eventType))].sort(),
      ['OrderAccepted', 'OrderCancelled', 'OrderCreated', 'OrderStatusChanged']);
    const byOrder = new Map();
    const seen = new Set();
    for (const { partition, key, event } of records) {
      assert.equal(key, String(event.orderId));
      assert.equal(event.schemaVersion, 1);
      assert.equal(Object.keys(event).length, 13);
      assert.equal(JSON.stringify(event).includes('Bearer'), false);
      if (seen.has(event.eventId)) continue;
      seen.add(event.eventId);
      const previous = byOrder.get(key);
      assert.equal(event.aggregateVersion, previous ? previous.aggregateVersion + 1 : 1);
      if (previous) {
        assert.equal(partition, previous.partition);
        assert.equal(event.previousStatus, previous.status);
      } else {
        assert.equal(event.eventType, 'OrderCreated');
        assert.equal(event.previousStatus, null);
      }
      byOrder.set(key, { ...event, partition });
    }
    const creation = stored.find(event => event.eventType === 'OrderCreated' && event.customerId === 'alice');
    assert.equal(creation.actorId, 'alice');
    const accepted = stored.find(event => event.eventType === 'OrderAccepted' && event.customerId === 'alice');
    assert.equal(accepted.actorId, 'operador');
    assertions += 8;
    return stored.length;
  }

  async function verifyFlow(sql, createAndCancel, restartOrders, withNotify) {
    console.log('Comprobando contrato Kafka, versiones y recuperación del publicador...');
    const initialCount = await verifyRecords(sql);
    await dockerCommand(['pause', container]);
    try {
      const id = await createAndCancel();
      assert.ok(id > 0);
      await waitFor(async () => Number(await sql('orders',
        'SELECT COUNT(*) FROM order_event_outbox WHERE published_at IS NULL AND attempts > 0')) > 0,
        'persistencia con Kafka detenido');
      assert.equal(await sql('orders', 'SELECT COUNT(*) FROM order_event_outbox WHERE published_at IS NULL'), '2');
      if (withNotify) {
        await waitFor(async () => await sql('orders',
          'SELECT COUNT(*) FROM notification_outbox WHERE published_at IS NULL') === '0',
          'RabbitMQ sigue publicando mientras Kafka está caído');
      }
      await restartOrders();
      assert.equal(await sql('orders', 'SELECT COUNT(*) FROM order_event_outbox WHERE published_at IS NULL'), '2');
      assertions += 3;
    } finally { await dockerCommand(['unpause', container]); }
    assert.equal(await verifyRecords(sql), initialCount + 2);
    assertions++;
    console.log(`SUCCESS: ${assertions} comprobaciones Orders -> PostgreSQL -> Kafka.`);
  }

  async function cleanUp() {
    if (container && /^[a-f0-9]{64}$/.test(container)) await dockerCommand(['rm', '-f', '-v', container]);
  }

  async function publish(key, payload) {
    assert.ok(dockerInput, 'Falta el helper de entrada de Kafka');
    await dockerInput(['exec', '-i', container, '/opt/kafka/bin/kafka-console-producer.sh',
      '--bootstrap-server', 'localhost:19092', '--topic', 'orders.events', '--sync',
      '--producer-property', 'acks=all', '--property', 'parse.key=true'], key + '\t' + payload + '\n');
  }

  return { start, verifyFlow, publish, cleanUp };
}
