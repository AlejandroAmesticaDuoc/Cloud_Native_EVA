# MS Pedidos360 MQ Admin

Microservicio administrador de RabbitMQ de Pedidos360 (EP3 DSY1107). Expone una API REST protegida con JWT para **crear y eliminar colas, exchanges y bindings**, vaciar colas, vigilar las DLQ y reprocesar (replay) mensajes muertos. Spring Boot 4.1.1, Java 21, Spring AMQP 4.1, puerto **8086**.

La topología completa del sistema, el contrato de mensajes y el guion de demostración están en [docs/RABBITMQ.md](../docs/RABBITMQ.md).

## Diseño

```text
controller/  QueueAdminController, ExchangeAdminController, BindingAdminController, DeadLetterQueueController
             -> solo validan la entrada (DTO + Bean Validation) y llaman métodos de alto nivel
service/     RabbitAdminService (interfaz) + RabbitAdminServiceImpl
             -> mutaciones por AMQP con RabbitAdmin/AmqpAdmin; replay con canal y publisher confirms
             DeadLetterMonitor -> @Scheduled: alerta WARN cuando una DLQ alcanza el umbral
client/      RabbitManagementClient -> lecturas y verificación de existencia/tipo en la API HTTP de management
config/      RabbitAdminConfig (RabbitAdmin + RestClient), MqAdminProperties, SecurityConfig, OpenApiConfig
dto/         CreateQueueRequest, CreateExchangeRequest, BindingRequest, ReplayRequest y respuestas
exception/   Excepciones de dominio + GlobalExceptionHandler (traducción centralizada a HTTP)
security/    Respuestas 401/403 con el formato común
```

Los controladores no conocen `RabbitAdmin`, `Channel` ni `RestClient`. mq-admin **no declara topología propia** (RabbitAdmin con `autoStartup=false` y `explicitDeclarationsOnly=true`): solo declara lo que se pide por la API y no vuelve a declarar lo que se eliminó.

## Seguridad

- `/api/v1/mq-admin/**`: JWT válido (emisor, audiencia y expiración) con scope `pedidos360.access` **y** rol `ADMIN`. Sin token: 401; otro rol o sin scope: 403.
- `GET /actuator/health/**`: público. Swagger/OpenAPI solo con `API_DOCS_ENABLED=true`. Todo lo demás se deniega.
- Las credenciales de RabbitMQ llegan por variables de entorno; la API de management usa las mismas (`spring.rabbitmq.username/password`).

## Endpoints (base `/api/v1/mq-admin`)

| Método | Ruta | Éxito | Errores |
|---|---|---|---|
| GET | `/queues` | 200 lista (tipo, argumentos, mensajes, consumidores, `protectedResource`) | 503 |
| GET | `/queues/{name}` | 200 | 400, 404 |
| POST | `/queues` | 201 + `Location: /api/v1/mq-admin/queues/{name}` | 400, 409 |
| DELETE | `/queues/{name}?ifUnused=false&ifEmpty=false` | 204 | 400, 404, 409 (protegida o precondición) |
| DELETE | `/queues/{name}/messages` | 200 `{queue, purged}` | 400, 404 |
| GET | `/exchanges` | 200 lista | 503 |
| POST | `/exchanges` | 201 + `Location` | 400, 409 |
| DELETE | `/exchanges/{name}?ifUnused=false` | 204 | 400, 404, 409 |
| GET | `/bindings?exchange=&queue=` | 200 lista (filtros opcionales) | 400, 404 |
| POST | `/bindings` | 201 + `Location: ...bindings?exchange=&queue=&routingKey=` | 400, 404, 409 |
| DELETE | `/bindings?exchange=&queue=&routingKey=` | 204 | 400, 404 |
| GET | `/dead-letter-queues` | 200 `[{queue, exists, messages, threshold, alert}]` | 503 |
| POST | `/dead-letter-queues/{name}/replay` | 200 `{replayed, failed}` | 400, 404 |

Todas las respuestas de error usan el formato común de Pedidos360:

```json
{"timestamp":"2026-10-08T22:00:00Z","status":400,"error":"Bad Request",
 "message":"La solicitud contiene datos inválidos: messageTtlMs: messageTtlMs debe ser mayor o igual a 1 ms",
 "path":"/api/v1/mq-admin/queues","traceId":"6a1f..."}
```

### Ejemplos con curl

```bash
TOKEN="<access token de un usuario ADMIN>"
API=http://localhost:8086/api/v1/mq-admin

# Crear un exchange topic y una cola con DLX, TTL y largo máximo
curl -i -X POST $API/exchanges -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"ex.demo.ep3","type":"TOPIC"}'
curl -i -X POST $API/queues -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"q.demo.ep3","type":"CLASSIC","deadLetterExchange":"cmd.dead.dlx","deadLetterRoutingKey":"demo.failed","messageTtlMs":604800000,"maxLength":10000}'

# Enlazar con comodín (solo exchanges topic) y listar
curl -i -X POST $API/bindings -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"exchange":"ex.demo.ep3","queue":"q.demo.ep3","routingKey":"demo.#"}'
curl -s "$API/bindings?exchange=ex.demo.ep3" -H "Authorization: Bearer $TOKEN"

# Desenlazar (el # va codificado como %23), vaciar y eliminar
curl -i -X DELETE "$API/bindings?exchange=ex.demo.ep3&queue=q.demo.ep3&routingKey=demo.%23" -H "Authorization: Bearer $TOKEN"
curl -i -X DELETE $API/queues/q.demo.ep3/messages -H "Authorization: Bearer $TOKEN"
curl -i -X DELETE "$API/queues/q.demo.ep3?ifEmpty=true" -H "Authorization: Bearer $TOKEN"
curl -i -X DELETE "$API/exchanges/ex.demo.ep3?ifUnused=true" -H "Authorization: Bearer $TOKEN"

# DLQ: profundidad/alerta y reproceso
curl -s $API/dead-letter-queues -H "Authorization: Bearer $TOKEN"
curl -i -X POST $API/dead-letter-queues/q.cmd.email.dlq/replay -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"maxMessages":10}'
```

En PowerShell usar `curl.exe` o `Invoke-RestMethod`. La colección [Pedidos360-MQAdmin](../postman/Pedidos360-MQAdmin.postman_collection.json) trae una solicitud por endpoint con ejemplos válidos e inválidos (variables `baseUrl` y `token`).

## Validaciones

| Dato | Regla | Error |
|---|---|---|
| Nombre de cola o exchange (cuerpo y ruta) | `^(?!amq\.)[A-Za-z0-9][A-Za-z0-9._-]{0,254}$`: obligatorio, 1-255 caracteres, sin el prefijo reservado `amq.` | 400 |
| Nombres en consultas (`GET /queues/{name}`, filtros de `GET /bindings`) | Igual, pero aceptan `amq.*` (recursos del broker) | 400 |
| `type` de cola | `CLASSIC` (por defecto) o `QUORUM` | 400 con valores permitidos |
| `durable` / `autoDelete` | Por defecto `true` / `false`. `durable=false` se rechaza: RabbitMQ 4.3 deniega colas transitorias no exclusivas. QUORUM exige `durable=true` y `autoDelete=false` (validación de clase `@ValidQueueSettings`) | 400 |
| `deadLetterExchange` | Mismo patrón de nombre y debe existir en el broker | 400 |
| `deadLetterRoutingKey` | Segmentos `[A-Za-z0-9_-]+` separados por punto, sin comodines, máx. 255; exige `deadLetterExchange` | 400 |
| `messageTtlMs` | 1 a 1209600000 (14 días) | 400 |
| `maxLength` | 1 a 1000000 | 400 |
| `type` de exchange | Obligatorio: `DIRECT`, `TOPIC`, `FANOUT` o `HEADERS` | 400 |
| `routingKey` de binding | Obligatoria, máx. 255, segmentos `[A-Za-z0-9_-]+`, `*` o `#` separados por punto; vacía solo para fanout/headers. `*` y `#` solo si el exchange es topic | 400 |
| `maxMessages` del replay | Obligatorio, 1 a 100 | 400 |
| JSON | Mal formado, tipo incorrecto, valor de enum inexistente o propiedad desconocida (`fail-on-unknown-properties=true`) | 400 con el campo afectado |
| `ifUnused`, `ifEmpty` | Booleanos | 400 |

## Reglas de negocio y códigos

- Crear algo que ya existe: **409**. Crear con un nombre de la topología base (`cmd.*`, `q.cmd.*`, `q.audit.*`): **409**, porque lo declara su microservicio con argumentos exactos.
- Eliminar o purgar algo inexistente: **404**.
- Recursos protegidos (`mqadmin.protected-resources`: 3 exchanges y 8 colas del contrato): no se eliminan, **409** con mensaje claro. Sí se pueden purgar (por ejemplo, una DLQ ya revisada).
- `ifUnused`/`ifEmpty` no cumplidos o `406 PRECONDITION_FAILED` del broker: **409** con el motivo.
- Broker o API de management caídos o con credenciales rechazadas: **503**.
- Los contadores de mensajes y consumidores vienen de la API de management, que los actualiza cada pocos segundos; las precondiciones `ifUnused`/`ifEmpty` las vuelve a comprobar el broker al eliminar (406 -> 409).
- Replay solo para las DLQ de `mqadmin.alerts.dead-letter-queues` (otra cola: **404**). Por cada mensaje: `basicGet` sin auto-ack, republicación con publisher confirm y `mandatory` al exchange y routing key originales (`x-death[0].exchange` + `routing-keys[0]`; sin `x-death`, directo a `x-first-death-queue`) y recién entonces `basicAck`. Si no hay destino, el exchange ya no existe, ninguna cola lo recibe o el broker no confirma, el mensaje vuelve a la DLQ con `basicNack(requeue=true)` y se cuenta en `failed`. El mensaje republicado conserva `messageId`, tipo y headers de trazabilidad, sin los headers `x-death*`, y lleva `x-replayed-from=<DLQ>`. Un mensaje que murió en la cola de auditoría vuelve directo a esa cola para no duplicar copias en las demás DLQ.

## Variables de entorno

| Variable | Predeterminado / uso |
|---|---|
| `MQ_ADMIN_PORT` | `8086` |
| `MQ_ADMIN_BIND_ADDRESS` | `127.0.0.1`; el Dockerfile usa `0.0.0.0` |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` / `RABBITMQ_VHOST` | `localhost` / `5672` / `pedidos360` |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | Obligatorias, sin valor predeterminado |
| `RABBITMQ_MANAGEMENT_URL` | `http://localhost:15672` (en Docker: `http://rabbitmq:15672`) |
| `MQ_ADMIN_ALERT_THRESHOLD` | `1`: alerta desde el primer mensaje en una DLQ |
| `MQ_ADMIN_ALERT_INTERVAL` | `30s` |
| `JWT_ISSUER_URI` / `JWT_AUDIENCE` | Los mismos del BFF |
| `API_DOCS_ENABLED` | `false` |
| `RABBITMQ_EMAIL_QUEUE` / `RABBITMQ_EMAIL_DLQ` | Opcionales; mantienen la lista protegida alineada si se cambia el nombre de la cola de correo |

Salud: `GET http://localhost:8086/actuator/health`; readiness incluye RabbitMQ (`/actuator/health/readiness`).

## Ejecutar

```powershell
Set-Location -LiteralPath 'D:\Cloud_Native_EVA'
docker compose --env-file .env -f compose.rabbitmq.yml up -d --build
Invoke-RestMethod http://localhost:8086/actuator/health
```

O fuera de Docker (con RabbitMQ ya levantado y las variables definidas en la terminal): `.\mvnw.cmd spring-boot:run`.

## Pruebas

```bash
./mvnw -B -ntp verify              # unitarias, @WebMvcTest, OpenAPI y seguridad JWT (100 pruebas, sin Docker)
./mvnw -B -ntp verify -Prabbit-it  # además RabbitAdminIT con Testcontainers (rabbitmq:4.3.5-management-alpine)
```

- `controller/*ControllerTest`: `@WebMvcTest` por controlador con `RabbitAdminService` simulado: 401 sin token, 403 sin ADMIN o sin scope, 400 por cada validación, 201 con `Location`, 204, 404, 409 y 503.
- `service/RabbitAdminServiceImplTest`: reglas con `AmqpAdmin`, `RabbitOperations` y `RabbitManagementClient` simulados (argumentos de `QueueBuilder`, 406 -> 409, protegidos, comodines, replay con confirm/ack/nack).
- `client/RabbitManagementClientTest`: vhost codificado (`/` -> `%2F`), autenticación básica, 404 -> vacío, 401 o caída -> 503.
- `security/JwtSecurityTest`: tokens firmados reales (emisor, audiencia, expiración, roles de Entra, scope).
- `config/OpenApiTest`: documento OpenAPI con todas las rutas y códigos; la API sigue protegida con la documentación activa.
- `RabbitAdminIT`: crea, enlaza, purga y elimina cola, exchange y binding reales; 409 al duplicar; 406 del broker -> 409; replay real desde `q.cmd.email.dlq` hacia `cmd.direct/email.send`.
