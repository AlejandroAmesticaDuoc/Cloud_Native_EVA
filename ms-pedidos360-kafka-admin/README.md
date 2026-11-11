# ms-pedidos360-kafka-admin

Microservicio administrador de Kafka de Pedidos360 (EP5). Permite **crear, listar, modificar y eliminar tópicos y sus
configuraciones**, **aumentar particiones** e **inspeccionar, reiniciar y eliminar consumer groups** (con su lag),
mediante una API REST protegida con JWT de Entra ID (scope `pedidos360.access` + rol `ADMIN`).

- Puerto: `8087` (`KAFKA_ADMIN_PORT`), solo en `127.0.0.1` fuera de Docker.
- Base: `/api/v1/kafka-admin`.
- Cliente: `org.apache.kafka.clients.admin.Admin` (spring-kafka 4.1 / kafka-clients 4.2), encapsulado en
  `service/KafkaAdminService` (interfaz) + `service/KafkaAdminServiceImpl`. Los controladores solo llaman métodos de
  alto nivel; las excepciones de Kafka se traducen a errores de dominio y luego a HTTP en `GlobalExceptionHandler`.

## Endpoints

| Método | Ruta | Descripción | Respuestas |
|---|---|---|---|
| GET | `/cluster` | Id del clúster, controlador y brokers | 200 / 503 |
| GET | `/topics?includeInternal=false` | Tópicos con particiones, réplicas y si están protegidos | 200 |
| GET | `/topics/{name}` | Líder, réplicas e ISR por partición + configuraciones vigentes | 200 / 404 |
| POST | `/topics` | Crea un tópico con particiones, réplicas y configuraciones | 201 + Location / 400 / 409 |
| PATCH | `/topics/{name}/configs` | Cambia configuraciones (`set`) o las vuelve al default (`reset`) | 200 / 400 / 404 |
| PATCH | `/topics/{name}/partitions` | Aumenta el total de particiones | 200 / 400 / 404 / 409 |
| DELETE | `/topics/{name}` | Elimina un tópico | 204 / 404 / 409 |
| GET | `/consumer-groups` | Consumer groups y su estado | 200 |
| GET | `/consumer-groups/{groupId}` | Miembros, particiones asignadas y lag por partición | 200 / 404 |
| POST | `/consumer-groups/{groupId}/offsets/reset` | Reinicia offsets (EARLIEST, LATEST o TIMESTAMP) | 200 / 400 / 404 / 409 |
| DELETE | `/consumer-groups/{groupId}` | Elimina un grupo sin consumidores activos | 204 / 404 / 409 |

Reglas:
- **Tópicos protegidos** (`kafkaadmin.protected-topics`: `orders.events`, `audit.timeline` y las tres DLT) y los internos
  (`__*`): se pueden consultar y **modificar sus configuraciones**, pero no eliminar ni cambiar sus particiones
  (cambiaría la partición de cada pedido y se perdería el orden de sus eventos) → 409.
- Configuraciones administrables (`kafkaadmin.allowed-configs`): `retention.ms`, `retention.bytes`, `cleanup.policy`,
  `min.insync.replicas`, `segment.ms`, `segment.bytes`, `max.message.bytes`, `compression.type`, `delete.retention.ms`,
  `min.compaction.lag.ms`, `max.compaction.lag.ms`. Otra clave → 400.
- Particiones 1..50, factor de replicación 1..3 (y nunca mayor que los brokers disponibles: Kafka lo rechaza → 400).
- Reiniciar offsets exige que el grupo **no tenga consumidores activos** (detener el servicio) → si no, 409.

## Validación (400 con mensaje claro)

- Nombre de tópico: `^(?!__)[A-Za-z0-9._-]{1,249}$` (sin espacios ni prefijo interno). Consumer group: `^[A-Za-z0-9._-]{1,255}$`.
- `cleanup.policy` ∈ `delete`, `compact`, `compact,delete`; `compression.type` ∈ `producer, uncompressed, gzip, snappy, lz4, zstd`;
  `retention.ms`/`retention.bytes` ≥ -1; `min.insync.replicas` 1..3; demás numéricos ≥ 0.
- `PATCH .../configs` debe traer al menos una clave en `set` o `reset`, y una clave no puede estar en ambos.
- `strategy=TIMESTAMP` exige `timestamp` (ISO-8601) y las demás estrategias no lo aceptan.
- JSON mal formado, tipo incorrecto, enum inexistente o propiedad desconocida → 400.

## Ejemplos

```bash
TOKEN="<access token de Entra con rol ADMIN>"
API=http://localhost:8087/api/v1/kafka-admin

curl -H "Authorization: Bearer $TOKEN" $API/cluster
curl -H "Authorization: Bearer $TOKEN" $API/topics/orders.events
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" $API/topics \
  -d '{"name":"demo.ep5","partitions":3,"replicationFactor":3,"configs":{"retention.ms":"86400000","cleanup.policy":"delete","min.insync.replicas":"2"}}'
curl -X PATCH -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" $API/topics/demo.ep5/configs \
  -d '{"set":{"retention.ms":"259200000"},"reset":["min.insync.replicas"]}'
curl -X PATCH -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" $API/topics/demo.ep5/partitions \
  -d '{"totalPartitions":6}'
curl -H "Authorization: Bearer $TOKEN" $API/consumer-groups/pedidos360-report-v1
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  $API/consumer-groups/pedidos360-report-timeline-v1/offsets/reset -d '{"topic":"audit.timeline","strategy":"EARLIEST"}'
curl -X DELETE -H "Authorization: Bearer $TOKEN" $API/topics/demo.ep5
```

Colección Postman: [`postman/Pedidos360-KafkaAdmin.postman_collection.json`](../postman/Pedidos360-KafkaAdmin.postman_collection.json).

## Variables de entorno

| Variable | Default | Uso |
|---|---|---|
| `KAFKA_ADMIN_PORT` / `KAFKA_ADMIN_BIND_ADDRESS` | `8087` / `127.0.0.1` | Puerto y dirección (el Dockerfile usa `0.0.0.0`) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Brokers (en Docker: `kafka-1:19092,kafka-2:19092,kafka-3:19092`) |
| `KAFKA_ORDERS_TOPIC` / `KAFKA_TIMELINE_TOPIC` | `orders.events` / `audit.timeline` | Nombres de los tópicos protegidos |
| `JWT_ISSUER_URI` / `JWT_AUDIENCE` | — | Emisor y audiencia de Entra ID |
| `API_DOCS_ENABLED` | `false` | Swagger UI en `/swagger-ui.html` |

## Ejecutar y probar

```bash
docker compose --env-file .env -f compose.kafka.yml -f compose.kafka-admin.yml up -d --build
cd ms-pedidos360-kafka-admin
./mvnw -B -ntp test                 # 63 pruebas (controladores, seguridad JWT, OpenAPI y servicio con Admin simulado)
./mvnw -B -ntp verify -Pkafka-it    # + KafkaAdminIT contra un broker Kafka 3.9 real (Testcontainers, requiere Docker)
```
