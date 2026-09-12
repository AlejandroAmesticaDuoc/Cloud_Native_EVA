import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { randomBytes, randomUUID } from 'node:crypto';
import { existsSync } from 'node:fs';
import { delimiter, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { setTimeout as delay } from 'node:timers/promises';
import { createServer } from 'node:net';

const execute = promisify(execFile);
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const docker = [process.env.DOCKER_CLI_PATH,
  process.env.LOCALAPPDATA && join(process.env.LOCALAPPDATA, 'Programs', 'DockerDesktop', 'resources', 'bin', 'docker.exe'),
  process.env.ProgramFiles && join(process.env.ProgramFiles, 'Docker', 'Docker', 'resources', 'bin', 'docker.exe')]
  .find(path => path && existsSync(path)) || 'docker';
const project = `p360-notify-test-${randomUUID()}`;
const withKafka = process.argv.includes('--kafka');
const environment = { ...process.env,
  POSTGRES_ADMIN_PASSWORD: randomBytes(24).toString('hex'), CATALOG_DB_PASSWORD: randomBytes(24).toString('hex'),
  ORDERS_DB_PASSWORD: randomBytes(24).toString('hex'), RABBITMQ_USERNAME: 'compose-test',
  RABBITMQ_PASSWORD: randomBytes(24).toString('hex'), RABBITMQ_VHOST: 'pedidos360',
  JWT_ISSUER_URI: 'https://login.microsoftonline.com/00000000-0000-0000-0000-000000000000/v2.0',
  JWT_AUDIENCE: 'compose-test-api', ORDERS_SERVICE_CLIENT_ID: '', ORDERS_SERVICE_CLIENT_SECRET: '',
  ORDERS_TOKEN_URI: '', ORDERS_CATALOG_SCOPE: '', NOTIFY_EMAIL_FROM: 'no-reply@pedidos360.test',
  NOTIFY_EMAIL_RECIPIENT: 'demo@pedidos360.test', RABBITMQ_EMAIL_QUEUE: 'q.cmd.email',
  RABBITMQ_EMAIL_DLQ: 'q.cmd.email.dlq', API_DOCS_ENABLED: 'false'
};
for (const key of ['POSTGRES_PORT', 'ORDERS_POSTGRES_PORT', 'BFF_PORT', 'CATALOG_PORT', 'ORDERS_PORT',
  'NOTIFY_PORT', 'RABBITMQ_PORT', 'RABBITMQ_MANAGEMENT_PORT', 'MAILPIT_PORT', 'MAILPIT_SMTP_PORT']) environment[key] = '0';
if (withKafka) {
  const probe = createServer();
  await new Promise((resolve, reject) => { probe.once('error', reject); probe.listen(0, '127.0.0.1', resolve); });
  environment.KAFKA_PORT = String(probe.address().port);
  await new Promise(resolve => probe.close(resolve));
  delete environment.KAFKA_CLUSTER_ID;
  environment.KAFKA_ORDERS_TOPIC = 'orders.events';
}
if (docker !== 'docker') {
  const pathKey = Object.keys(environment).find(key => key.toLowerCase() === 'path') || 'PATH';
  environment[pathKey] = dirname(docker) + delimiter + (environment[pathKey] || '');
}
const compose = ['compose', '--env-file', '.env.example', '-p', project,
  '-f', 'compose.postgres.yml', '-f', 'compose.catalog.yml', '-f', 'compose.orders.yml', '-f', 'compose.notify.yml'];
if (withKafka) compose.push('-f', 'compose.kafka.yml');

async function run(args, timeout = 120000) {
  const result = await execute(docker, args, {
    cwd: root, env: environment, windowsHide: true, timeout, maxBuffer: 8 * 1024 * 1024
  });
  return result.stdout.trim();
}

async function ready(service, port) {
  const binding = await run([...compose, 'port', service, String(port)]);
  assert.match(binding, /^127\.0\.0\.1:\d+$/);
  const endpoint = `http://${binding}/actuator/health`;
  const deadline = Date.now() + 120000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(endpoint, { signal: AbortSignal.timeout(5000) });
      if (response.ok && (await response.json()).status === 'UP') return;
    } catch {}
    await delay(500);
  }
  throw new Error(`${service} no llegó a UP`);
}

try {
  console.log('Validando Compose y construyendo las imágenes de Orders y Notify...');
  const configuration = JSON.parse(await run([...compose, 'config', '--format', 'json']));
  assert.equal(Object.keys(configuration.services).length, withKafka ? 10 : 8);
  for (const service of Object.values(configuration.services)) {
    for (const port of service.ports || []) assert.equal(port.host_ip, '127.0.0.1');
  }
  assert.equal(configuration.services.notify.environment.SMTP_HOST, 'mailpit');
  assert.equal(configuration.services.orders.environment.ORDERS_NOTIFICATIONS_ENABLED, 'true');
  if (withKafka) assert.match(configuration.services.kafka.environment.CLUSTER_ID, /^[A-Za-z0-9_-]{22}$/);
  await run([...compose, 'up', '-d', '--build'], 600000);
  console.log('Comprobando salud y ejecución sin root de los cuatro servicios Java...');
  for (const [service, port] of [['bff', 8080], ['catalog', 8082], ['orders', 8081], ['notify', 8083]]) {
    await ready(service, port);
    const id = await run([...compose, 'ps', '-q', service]);
    assert.match(id, /^[a-f0-9]{64}$/);
    assert.equal(await run(['exec', id, 'id', '-u']), '10001');
    console.log(`${service}: UP, usuario 10001`);
  }
  if (withKafka) {
    const id = await run([...compose, 'ps', '-q', 'kafka']);
    assert.match(id, /^[a-f0-9]{64}$/);
    const topic = await run(['exec', id, '/opt/kafka/bin/kafka-topics.sh',
      '--bootstrap-server', 'kafka:19092', '--describe', '--topic', 'orders.events']);
    assert.match(topic, /PartitionCount:\s*3/);
    assert.equal(configuration.services.orders.environment.KAFKA_BOOTSTRAP_SERVERS, 'kafka:19092');
    const initializer = await run([...compose, 'ps', '-a', '-q', 'kafka-init']);
    assert.match(initializer, /^[a-f0-9]{64}$/);
    assert.equal(await run(['inspect', '--format', '{{.State.ExitCode}}', initializer]), '0');
    console.log('Kafka: tópico orders.events creado con tres particiones.');
  }
  console.log(`SUCCESS: ${withKafka ? 'nueve contenedores activos y un inicializador' : 'ocho contenedores'}; cuatro servicios Java verificados en Docker Compose.`);
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
} finally {
  assert.match(project, /^p360-notify-test-[a-f0-9-]{36}$/);
  const ids = (await run([...compose, 'ps', '-a', '-q'])).split(/\s+/).filter(Boolean);
  for (const id of ids) {
    assert.match(id, /^[a-f0-9]{64}$/);
    assert.equal(await run(['inspect', '--format', '{{index .Config.Labels "com.docker.compose.project"}}', id]), project);
  }
  await run([...compose, 'down', '--volumes'], 120000);
  console.log('Proyecto Compose temporal retirado; volúmenes de desarrollo intactos.');
}
