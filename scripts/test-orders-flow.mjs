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
  CATALOG_DB_PASSWORD: randomBytes(24).toString('hex'),
  ORDERS_DB_PASSWORD: randomBytes(24).toString('hex')
};
const environment = { ...process.env, ...credentials };
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
let orders;
let catalogTarget;
let failNextStock;
let applicationTokenRequests = 0;
let serviceStockRequests = 0;
const serviceClientId = 'orders-integration-client';
const serviceSecret = randomBytes(24).toString('hex');

const authServer = createServer(async (request, response) => {
  let body;
  if (request.url === '/.well-known/openid-configuration') {
    body = { issuer, jwks_uri: `${issuer}/jwks` };
  } else if (request.url === '/jwks') {
    body = { keys: [publicKey] };
  } else if (request.url === '/token' && request.method === 'POST') {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const form = new URLSearchParams(Buffer.concat(chunks).toString());
    if (form.get('grant_type') !== 'client_credentials' || form.get('client_id') !== serviceClientId
        || form.get('client_secret') !== serviceSecret || form.get('scope') !== `api://${audience}/.default`) {
      response.writeHead(400, { 'Content-Type': 'application/json' }).end('{"error":"invalid_client"}');
      return;
    }
    applicationTokenRequests++;
    body = { access_token: token(['CATALOG_STOCK_WRITE'], { scp: undefined, azp: serviceClientId }),
      token_type: 'Bearer', expires_in: 600 };
  } else {
    response.writeHead(404).end();
    return;
  }
  response.writeHead(200, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify(body));
});

const catalogProxy = createServer(async (incoming, outgoing) => {
  try {
    const chunks = [];
    for await (const chunk of incoming) chunks.push(chunk);
    const body = Buffer.concat(chunks);
    const headers = {};
    for (const name of ['authorization', 'content-type', 'accept', 'x-trace-id']) {
      if (incoming.headers[name]) headers[name] = incoming.headers[name];
    }
    const internal = incoming.url.startsWith('/internal/');
    if (internal) {
      const claims = JSON.parse(Buffer.from(headers.authorization.split('.')[1], 'base64url').toString());
      assert.equal(claims.azp, serviceClientId);
      assert.equal(claims.scp, undefined);
      assert.deepEqual(claims.roles, ['CATALOG_STOCK_WRITE']);
      serviceStockRequests++;
    }
    const result = await fetch(catalogTarget + incoming.url, { method: incoming.method, headers,
      body: body.length ? body : undefined, signal: AbortSignal.timeout(8000) });
    const content = await result.text();
    const operation = incoming.url.endsWith('/release') ? 'release' : 'deduct';
    if (internal && result.status === 204 && failNextStock === operation) {
      failNextStock = undefined;
      outgoing.writeHead(503).end();
      return;
    }
    const responseHeaders = { 'Content-Type': result.headers.get('Content-Type') || 'application/json' };
    for (const name of ['X-Stock-Result', 'X-Trace-Id', 'WWW-Authenticate']) {
      if (result.headers.has(name)) responseHeaders[name] = result.headers.get(name);
    }
    outgoing.writeHead(result.status, responseHeaders);
    outgoing.end(content);
  } catch {
    outgoing.writeHead(502).end();
  }
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
  await stop(orders);
  await stop(catalog);
  if (catalogProxy.listening) await new Promise(resolve => catalogProxy.close(resolve));
  if (authServer.listening) await new Promise(resolve => authServer.close(resolve));
  if (databaseId && /^[a-f0-9]{64}$/.test(databaseId)) {
    await dockerCommand(['rm', '-f', '-v', databaseId]);
    databaseId = null;
  }
}

try {
  for (const name of ['ms-pedidos360-catalog', 'ms-pedidos360-orders', 'ms-pedidos360-bff']) {
    assert.ok(existsSync(jar(name)), `Primero compila ${name} con Maven: falta el JAR`);
  }
  await dockerCommand(['info', '--format', '{{.ServerVersion}}']);
  await new Promise(resolve => authServer.listen(0, '127.0.0.1', resolve));
  issuer = `http://127.0.0.1:${authServer.address().port}`;
  console.log('Preparando bases temporales para Catalog y Orders...');
  databaseId = await dockerCommand(['run', '-d', '--rm', '--name', `pedidos360-orders-test-${randomUUID()}`,
    '--label', 'pedidos360.test=true', '-p', '127.0.0.1::5432',
    '-e', 'POSTGRES_PASSWORD', '-e', 'CATALOG_DB_PASSWORD', '-e', 'ORDERS_DB_PASSWORD',
    '--mount', `type=bind,source=${join(root, 'infra', 'postgres', '001-create-catalog.sh')},target=/docker-entrypoint-initdb.d/001-create-catalog.sh,readonly`,
    '--mount', `type=bind,source=${join(root, 'infra', 'postgres', '002-create-orders.sh')},target=/docker-entrypoint-initdb.d/002-create-orders.sh,readonly`,
    'postgres:17.11-alpine']);
  assert.match(databaseId, /^[a-f0-9]{64}$/);
  const binding = await dockerCommand(['port', databaseId, '5432/tcp']);
  const databasePort = Number(binding.match(/:(\d+)\s*$/)?.[1]);
  assert.ok(databasePort > 0);
  const sql = async (service, statement) => dockerCommand(['exec', '-e', 'PGPASSWORD', databaseId, 'psql',
    '-h', '127.0.0.1', '-U', `pedidos360_${service}`, '-d', `pedidos360_${service}`, '-Atqc', statement],
    { PGPASSWORD: credentials[`${service.toUpperCase()}_DB_PASSWORD`] });
  await waitFor(async () => {
    try { return await sql('orders', 'SELECT 1') === '1'; } catch { return false; }
  }, 'inicialización PostgreSQL');
  catalog = launch('ms-pedidos360-catalog', {
    CATALOG_DB_URL: `jdbc:postgresql://127.0.0.1:${databasePort}/pedidos360_catalog`,
    ORDERS_SERVICE_CLIENT_ID: serviceClientId
  });
  catalogTarget = await ready(catalog, 'Catalog');
  await new Promise(resolve => catalogProxy.listen(0, '127.0.0.1', resolve));
  const ordersEnv = {
    ORDERS_DB_URL: `jdbc:postgresql://127.0.0.1:${databasePort}/pedidos360_orders`,
    CATALOG_SERVICE_URL: `http://127.0.0.1:${catalogProxy.address().port}`,
    ORDERS_SERVICE_CLIENT_ID: serviceClientId, ORDERS_SERVICE_CLIENT_SECRET: serviceSecret,
    ORDERS_TOKEN_URI: `${issuer}/token`, ORDERS_CATALOG_SCOPE: `api://${audience}/.default`
  };
  orders = launch('ms-pedidos360-orders', ordersEnv);
  let ordersUrl = await ready(orders, 'Orders');
  bff = launch('ms-pedidos360-bff', { CATALOG_SERVICE_URL: catalogTarget, ORDERS_SERVICE_URL: ordersUrl });
  let bffUrl = await ready(bff, 'BFF');
  const admin = token();
  const operator = token(['OPERADOR'], { oid: 'operador' });
  const alice = token(['CLIENTE'], { oid: 'alice' });
  const bob = token(['CLIENTE'], { oid: 'bob' });
  const auditor = token(['AUDITOR'], { oid: 'auditor' });
  const api = '/api/v1/orders';
  const products = '/api/v1/catalog';
  const getStock = async id => (await request(bffUrl, 'GET', `${products}/${id}`, 200, alice)).stock;
  const createOrder = async (quantity = 2, jwt = alice, productId = product.id) =>
    request(bffUrl, 'POST', api, 201, jwt, { items: [{ productId, quantity }] });
  const setStatus = async (id, status, expected = 200) =>
    request(bffUrl, 'PATCH', `${api}/${id}/status`, expected, operator, { status });
  console.log('Comprobando autenticación, propiedad, precios y estados...');
  await request(bffUrl, 'GET', api, 401);
  await request(ordersUrl, 'GET', api, 401);
  await request(bffUrl, 'GET', api, 403, auditor);
  await request(bffUrl, 'POST', api, 403, admin, { items: [{ productId: 1, quantity: 1 }] });
  await request(bffUrl, 'POST', api, 400, alice, { items: [{ productId: 1, quantity: 1.5 }] });
  await request(ordersUrl, 'POST', api, 403, alice,
    { customerId: 'bob', items: [{ productId: 1, quantity: 1 }] });
  const product = await request(bffUrl, 'POST', products, 201, admin, { name: 'Producto Orders', price: 1250.50, stock: 10 });
  const first = await createOrder();
  assert.equal(first.customerId, 'alice');
  assert.equal(first.status, 'CREADO');
  assert.equal(first.total, 2501);
  assert.equal(first.items[0].unitPrice, 1250.50);
  assert.equal(await getStock(product.id), 10);
  assert.equal(first.pendingStatus, undefined);
  await request(bffUrl, 'PUT', `${products}/${product.id}`, 200, admin, { name: 'Precio nuevo', price: 2000 });
  assert.equal((await request(bffUrl, 'GET', `${api}/${first.id}`, 200, alice)).total, 2501);
  await request(bffUrl, 'GET', `${api}/${first.id}`, 403, bob);
  await request(bffUrl, 'POST', `${api}/${first.id}/cancel`, 403, bob);
  await request(ordersUrl, 'GET', `${api}/${first.id}`, 403, bob);
  await request(ordersUrl, 'GET', `${api}?customerId=alice`, 403, bob);
  await request(bffUrl, 'PATCH', `${api}/${first.id}/status`, 403, alice, { status: 'ACEPTADO' });
  assert.deepEqual(await request(bffUrl, 'GET', api, 200, bob), []);
  await setStatus(first.id, 'DESPACHADO', 409);
  await setStatus(first.id, 'ACEPTADO');
  await setStatus(first.id, 'ACEPTADO');
  assert.equal(await getStock(product.id), 8);
  await request(bffUrl, 'POST', `${api}/${first.id}/cancel`, 204, alice);
  await request(bffUrl, 'POST', `${api}/${first.id}/cancel`, 204, alice);
  assert.equal(await getStock(product.id), 10);
  const unavailable = await createOrder(11);
  await setStatus(unavailable.id, 'ACEPTADO', 409);
  assert.equal((await request(bffUrl, 'GET', `${api}/${unavailable.id}`, 200, alice)).status, 'CREADO');
  assert.equal(await getStock(product.id), 10);
  await request(bffUrl, 'POST', `${api}/${unavailable.id}/cancel`, 204, alice);
  const delivered = await createOrder(1);
  for (const status of ['ACEPTADO', 'EN_PREPARACION', 'DESPACHADO', 'ENTREGADO']) await setStatus(delivered.id, status);
  await request(bffUrl, 'POST', `${api}/${delivered.id}/cancel`, 409, alice);
  assert.equal(await getStock(product.id), 9);
  const operatorOrder = await createOrder(1, operator);
  assert.equal(operatorOrder.customerId, 'operador');
  await request(bffUrl, 'POST', `${api}/${operatorOrder.id}/cancel`, 204, admin);
  const stockRoute = '/internal/v1/catalog/stock/deductions';
  await request(catalogTarget, 'POST', stockRoute, 403, alice, { orderId: 999, items: [{ productId: product.id, quantity: 1 }] });
  await request(bffUrl, 'POST', stockRoute, 403, admin, { orderId: 999, items: [{ productId: product.id, quantity: 1 }] });
  console.log('Simulando pérdida de respuesta y reinicio con una operación pendiente...');
  const recovery = await createOrder(2);
  failNextStock = 'deduct';
  await setStatus(recovery.id, 'ACEPTADO', 502);
  assert.equal(await getStock(product.id), 7);
  assert.equal(await sql('orders', `SELECT status || '|' || pending_status FROM purchase_orders WHERE id = ${recovery.id}`),
    'CREADO|ACEPTADO');
  await request(bffUrl, 'POST', `${api}/${recovery.id}/cancel`, 409, alice);
  await stop(bff);
  await stop(orders);
  orders = launch('ms-pedidos360-orders', ordersEnv);
  ordersUrl = await ready(orders, 'Orders reiniciado');
  bff = launch('ms-pedidos360-bff', { CATALOG_SERVICE_URL: catalogTarget, ORDERS_SERVICE_URL: ordersUrl });
  bffUrl = await ready(bff, 'BFF reiniciado');
  await setStatus(recovery.id, 'ACEPTADO');
  assert.equal(await getStock(product.id), 7);
  failNextStock = 'release';
  await request(bffUrl, 'POST', `${api}/${recovery.id}/cancel`, 502, alice);
  assert.equal(await getStock(product.id), 9);
  await setStatus(recovery.id, 'EN_PREPARACION', 409);
  await request(bffUrl, 'POST', `${api}/${recovery.id}/cancel`, 204, alice);
  assert.equal(await getStock(product.id), 9);
  assert.equal(await sql('orders', `SELECT status FROM purchase_orders WHERE id = ${recovery.id}`), 'CANCELADO');
  assert.equal(await sql('catalog', `SELECT COUNT(*) FROM stock_deductions WHERE order_id = ${recovery.id}`), '1');
  assert.ok(serviceStockRequests >= 6);
  assert.equal(applicationTokenRequests, 2);
  checks += 5;
  const raceProduct = await request(bffUrl, 'POST', products, 201, admin, { name: 'Concurrencia', price: 100, stock: 3 });
  const raceA = await createOrder(2, alice, raceProduct.id);
  const raceB = await createOrder(2, bob, raceProduct.id);
  const concurrent = await Promise.all([raceA, raceB].map(order => fetch(`${bffUrl}${api}/${order.id}/status`, {
    method: 'PATCH', headers: { Authorization: `Bearer ${operator}`, 'Content-Type': 'application/json' },
    body: '{"status":"ACEPTADO"}', signal: AbortSignal.timeout(15000) })));
  assert.deepEqual(concurrent.map(response => response.status).sort(), [200, 409]);
  assert.equal(await getStock(raceProduct.id), 1);
  checks++;
  const bobOrders = await request(bffUrl, 'GET', api, 200, bob);
  assert.ok(bobOrders.length > 0 && bobOrders.every(order => order.customerId === 'bob'));
  const managedOrders = await request(bffUrl, 'GET', api, 200, admin);
  assert.ok(managedOrders.length > bobOrders.length);
  await stop(orders);
  await request(bffUrl, 'GET', api, 502, alice);
  console.log(`SUCCESS: ${checks} comprobaciones BFF -> Orders -> Catalog -> PostgreSQL.`);
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
} finally {
  await cleanUp();
  console.log('Procesos y bases temporales retirados; datos de desarrollo intactos.');
}
