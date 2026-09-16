// Prueba local aislada: no usa cuentas Entra reales ni la base de desarrollo.
// Requiere Node 22+, Java y Docker. Ejecutar después de compilar ambos JAR.
import assert from 'node:assert/strict';
import { generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto';
import { spawn, execFile } from 'node:child_process';
import { existsSync } from 'node:fs';
import { createServer } from 'node:http';
import { delimiter, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { setTimeout as delay } from 'node:timers/promises';

const execute = promisify(execFile);
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const jar = name => join(root, name, 'target', `${name}-0.0.1-SNAPSHOT.jar`);
const docker = [process.env.DOCKER_CLI_PATH,
  process.env.LOCALAPPDATA && join(process.env.LOCALAPPDATA, 'Programs', 'DockerDesktop', 'resources', 'bin', 'docker.exe'),
  process.env.ProgramFiles && join(process.env.ProgramFiles, 'Docker', 'Docker', 'resources', 'bin', 'docker.exe')]
  .find(path => path && existsSync(path)) || 'docker';
const credentials = {
  POSTGRES_PASSWORD: randomBytes(24).toString('hex'),
  CATALOG_DB_PASSWORD: randomBytes(24).toString('hex')
};
const environment = { ...process.env, ...credentials };
// Una terminal abierta antes de instalar Docker puede no tener sus helpers en PATH.
if (docker !== 'docker') {
  const pathKey = Object.keys(environment).find(key => key.toLowerCase() === 'path') || 'PATH';
  environment[pathKey] = dirname(docker) + delimiter + (environment[pathKey] || '');
}
const key = generateKeyPairSync('rsa', { modulusLength: 2048 });
const kid = randomUUID();
const publicKey = { ...key.publicKey.export({ format: 'jwk' }), kid, use: 'sig', alg: 'RS256' };
const audience = 'pedidos360-local-integration';
let issuer;
let databaseId;
let catalog;
let bff;
let checks = 0;

const authServer = createServer((request, response) => {
  let body;
  if (request.url === '/.well-known/openid-configuration') {
    body = { issuer, jwks_uri: `${issuer}/jwks` };
  } else if (request.url === '/jwks') {
    body = { keys: [publicKey] };
  } else {
    response.writeHead(404).end();
    return;
  }
  response.writeHead(200, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify(body));
});

function token(roles = ['ADMIN'], overrides = {}) {
  const now = Math.floor(Date.now() / 1000);
  const claims = { iss: issuer, aud: audience, sub: 'integration-user', oid: 'integration-user',
    scp: 'pedidos360.access', roles, iat: now, nbf: now - 5, exp: now + 600, ...overrides };
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const message = `${encode({ alg: 'RS256', typ: 'JWT', kid })}.${encode(claims)}`;
  return `${message}.${sign('RSA-SHA256', Buffer.from(message), key.privateKey).toString('base64url')}`;
}

async function dockerCommand(args, extraEnv = {}) {
  return (await execute(docker, args, { cwd: root, env: { ...environment, ...extraEnv },
    windowsHide: true, timeout: 120000, maxBuffer: 1024 * 1024 })).stdout.trim();
}

async function waitFor(check, description, timeout = 90000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    const result = await check();
    if (result) return result;
    await delay(250);
  }
  throw new Error(`Tiempo de espera agotado: ${description}`);
}

function launch(name, variables) {
  const executable = process.env.JAVA_HOME
    ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const child = spawn(executable, ['-jar', jar(name), '--server.port=0', '--server.address=127.0.0.1'], {
    cwd: root, env: { ...environment, SPRING_PROFILES_ACTIVE: 'integration-smoke',
      JWT_ISSUER_URI: issuer, JWT_AUDIENCE: audience, API_DOCS_ENABLED: 'true', ...variables },
    windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']
  });
  const application = { child, output: '', port: null, error: null };
  const collect = chunk => {
    application.output = (application.output + chunk.toString()).slice(-24000);
    const port = application.output.match(/Tomcat started on port (\d+)/);
    if (port) application.port = Number(port[1]);
  };
  child.stdout.on('data', collect);
  child.stderr.on('data', collect);
  child.on('error', error => { application.error = error; });
  return application;
}

async function ready(application, name) {
  await waitFor(async () => {
    if (application.error) throw application.error;
    if (application.child.exitCode !== null) {
      throw new Error(`${name} no pudo iniciar: ${application.output.slice(-5000)}`);
    }
    if (!application.port) return false;
    try {
      const result = await fetch(`http://127.0.0.1:${application.port}/actuator/health`,
        { signal: AbortSignal.timeout(2000) });
      return result.ok;
    } catch { return false; }
  }, `inicio de ${name}`);
  return `http://127.0.0.1:${application.port}`;
}

async function stop(application) {
  if (!application || application.child.exitCode !== null || application.error) return;
  const closed = new Promise(resolve => application.child.once('exit', resolve));
  application.child.kill();
  await Promise.race([closed, delay(5000)]);
  if (application.child.exitCode === null) application.child.kill('SIGKILL');
}

async function request(base, method, path, expected, accessToken, body, trace = 'integration-catalog-001') {
  const headers = { Accept: 'application/json', 'X-Trace-Id': trace };
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const result = await fetch(base + path, { method, headers,
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000) });
  const content = await result.text();
  assert.equal(result.status, expected, `${method} ${path}: se esperaba ${expected}, se recibió ${result.status}: ${content}`);
  assert.match(result.headers.get('X-Trace-Id') || '', /^[A-Za-z0-9._-]{1,100}$/);
  if (/^[A-Za-z0-9._-]{1,100}$/.test(trace)) assert.equal(result.headers.get('X-Trace-Id'), trace);
  const data = content ? JSON.parse(content) : null;
  if (expected >= 400) assert.equal(data.traceId, result.headers.get('X-Trace-Id'));
  checks++;
  return data;
}

async function cleanUp() {
  await stop(bff);
  await stop(catalog);
  if (authServer.listening) await new Promise(resolve => authServer.close(resolve));
  // Solo se elimina el contenedor temporal que ESTE script creó, nunca por glob o nombre compartido.
  if (databaseId && /^[a-f0-9]{64}$/.test(databaseId)) {
    await dockerCommand(['rm', '-f', '-v', databaseId]);
    databaseId = null;
  }
}

try {
  for (const name of ['ms-pedidos360-catalog', 'ms-pedidos360-bff']) {
    assert.ok(existsSync(jar(name)), `Primero compila ${name} con Maven: falta el JAR`);
  }
  await dockerCommand(['info', '--format', '{{.ServerVersion}}']);
  await new Promise(resolve => authServer.listen(0, '127.0.0.1', resolve));
  issuer = `http://127.0.0.1:${authServer.address().port}`;
  console.log('Iniciando PostgreSQL temporal, aislado de la base de desarrollo...');
  databaseId = await dockerCommand(['run', '-d', '--rm', '--name', `pedidos360-test-${randomUUID()}`,
    '--label', 'pedidos360.test=true', '-p', '127.0.0.1::5432',
    '-e', 'POSTGRES_PASSWORD', '-e', 'CATALOG_DB_PASSWORD',
    '--mount', `type=bind,source=${join(root, 'infra', 'postgres', '001-create-catalog.sh')},target=/docker-entrypoint-initdb.d/001-create-catalog.sh,readonly`,
    'postgres:17.11-alpine']);
  assert.match(databaseId, /^[a-f0-9]{64}$/);
  const portBinding = await dockerCommand(['port', databaseId, '5432/tcp']);
  const databasePort = Number(portBinding.match(/:(\d+)\s*$/)?.[1]);
  assert.ok(databasePort > 0);
  await waitFor(async () => {
    try {
      return await dockerCommand(['exec', '-e', 'PGPASSWORD', databaseId, 'psql', '-h', '127.0.0.1',
        '-U', 'pedidos360_catalog', '-d', 'pedidos360_catalog', '-Atqc', 'SELECT 1'],
        { PGPASSWORD: credentials.CATALOG_DB_PASSWORD }) === '1';
    } catch { return false; }
  }, 'creación del usuario PostgreSQL');

  catalog = launch('ms-pedidos360-catalog', {
    CATALOG_DB_URL: `jdbc:postgresql://127.0.0.1:${databasePort}/pedidos360_catalog`,
    CATALOG_DB_USERNAME: 'pedidos360_catalog'
  });
  const catalogUrl = await ready(catalog, 'Catalog');
  bff = launch('ms-pedidos360-bff', { CATALOG_SERVICE_URL: catalogUrl,
    BFF_HTTP_CONNECT_TIMEOUT: '2s', BFF_HTTP_READ_TIMEOUT: '3s' });
  const bffUrl = await ready(bff, 'BFF');
  console.log('BFF y Catalog iniciados; verificando HTTP, JWT, CRUD, stock y persistencia...');
  const admin = token();
  const client = token(['CLIENTE']);
  const api = '/api/v1/catalog';
  await request(bffUrl, 'GET', api, 401);
  await request(catalogUrl, 'GET', api, 401);
  await request(bffUrl, 'POST', api, 403, client, { name: 'No permitido', price: 1, stock: 1 });
  await request(bffUrl, 'GET', api, 403, token(['ADMIN'], { scp: 'otro.scope' }));
  await request(bffUrl, 'GET', api, 401, token(['ADMIN'], { aud: 'otra-api' }));
  await request(bffUrl, 'GET', api, 401, token(['ADMIN'], { exp: undefined }));
  await request(bffUrl, 'GET', api, 401, token(['ADMIN'], { exp: 1 }));
  await request(bffUrl, 'GET', api, 401, undefined, undefined, 'x'.repeat(101));
  assert.deepEqual(await request(bffUrl, 'GET', api, 200, client), []);
  const product = await request(bffUrl, 'POST', api, 201, admin, { name: 'Café integración', price: 2500.50, stock: 10 });
  assert.ok(Number.isSafeInteger(product.id) && product.id > 0);
  assert.equal(product.active, true);
  assert.equal(product.version, undefined);
  await request(bffUrl, 'GET', `${api}/texto`, 400, admin);
  await request(bffUrl, 'POST', api, 400, admin, { name: 'Inválido', price: 0, stock: -1 });
  await request(bffUrl, 'PUT', `${api}/${product.id}`, 200, admin, { name: 'Café actualizado', price: 3000 });
  await request(bffUrl, 'PATCH', `${api}/${product.id}/stock`, 200, admin, { stock: 7 });
  const deduction = { orderId: 7001, items: [{ productId: product.id, quantity: 2 }] };
  const stock = '/internal/v1/catalog/stock/deductions';
  await request(bffUrl, 'POST', stock, 403, admin, deduction);
  await request(catalogUrl, 'POST', stock, 204, admin, deduction);
  await request(catalogUrl, 'POST', stock, 204, admin, deduction);
  assert.equal((await request(bffUrl, 'GET', `${api}/${product.id}`, 200, client)).stock, 5);
  await request(catalogUrl, 'POST', `${stock}/7001/release`, 204, admin);
  await request(catalogUrl, 'POST', `${stock}/7001/release`, 204, admin);
  assert.equal((await request(bffUrl, 'GET', `${api}/${product.id}`, 200, client)).stock, 7);
  await request(bffUrl, 'DELETE', `${api}/${product.id}`, 204, admin);
  await request(bffUrl, 'GET', `${api}/${product.id}`, 404, client);
  assert.deepEqual(await request(bffUrl, 'GET', api, 200, client), []);
  const stored = await dockerCommand(['exec', '-e', 'PGPASSWORD', databaseId, 'psql', '-h', '127.0.0.1',
    '-U', 'pedidos360_catalog', '-d', 'pedidos360_catalog', '-Atqc',
    `SELECT active, stock FROM products WHERE id = ${product.id}`], { PGPASSWORD: credentials.CATALOG_DB_PASSWORD });
  assert.equal(stored, 'f|7');
  checks++;
  await stop(catalog);
  await request(bffUrl, 'GET', api, 502, client);
  console.log(`SUCCESS: ${checks} comprobaciones de integración BFF -> Catalog -> PostgreSQL.`);
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
} finally {
  await cleanUp();
  console.log('Prueba finalizada: procesos y base temporales retirados; datos de desarrollo intactos.');
}
