# Notificaciones con RabbitMQ

Este documento cubre la ruta de **correo** (Orders -> RabbitMQ -> Notify -> SMTP). La topología completa (tickets de cocina, boletas, DLQ, auditoría, mq-admin), el contrato de mensajes, la tabla ACK/NACK y el guion de demostración están en [RabbitMQ en Pedidos360](RABBITMQ.md).

## Qué implementamos

Orders genera un comando de correo al crear un pedido y después de cada cambio efectivo de estado. Notify lo recibe y envía un correo por SMTP. Repetir una aceptación o cancelación ya completada no genera otro aviso.

```text
Orders -> PostgreSQL de Orders (pedido + comando pendiente en notification_outbox)
              |
              v  cmd.direct [email.send]  /  cmd.topic [email.send.high] (cancelación)
           RabbitMQ -> q.cmd.email -> Notify (EmailCommandListener, ACK manual) -> Mailpit
                           |
                           v  nack sin requeue -> cmd.dead.dlx [email.send]
                     q.cmd.email.dlq (7 días) + copia en q.audit.dead-letters
```

Mailpit captura los correos para verlos en el navegador; la configuración local no envía correos a Internet. Como todavía no hay un directorio de correos de clientes, todos los avisos llegan a `NOTIFY_EMAIL_RECIPIENT`, una dirección de demostración. No se interpreta `customerId` como correo ni se acepta un destinatario enviado por el frontend. Este bloque no modifica rutas públicas, BFF ni Angular.

## Contrato del mensaje

`EmailCommand` v1 (JSON, `application/json`, entrega persistente, `messageId` igual a `eventId`, `type` igual a la routing key y headers `x-trace-id` y `x-schema-version`):

```json
{
  "schemaVersion": 1,
  "eventId": "619e3133-dc47-4dfc-a121-54aa69c3d5aa",
  "orderId": 15,
  "customerId": "identificador-interno-del-cliente",
  "status": "ACEPTADO",
  "occurredAt": "2026-09-12T05:00:00Z",
  "traceId": "pedido-demo-15"
}
```

Estados: `CREADO`, `ACEPTADO`, `EN_PREPARACION`, `DESPACHADO`, `ENTREGADO` y `CANCELADO`. Las cancelaciones viajan por `cmd.topic` con `email.send.high` y llegan a la misma cola por el patrón `email.#`. Notify rechaza campos desconocidos, versiones diferentes, identificadores inválidos, estados desconocidos y mensajes mayores a 8192 bytes. No se incluyen Access Tokens, contraseñas, precios ni direcciones entregadas por el usuario.

## Fallos y reintentos

- El pedido y sus comandos se guardan en la misma transacción (`notification_outbox`, con `command_type` `EMAIL`, `EMAIL_PRIORITY`, `KITCHEN_TICKET` o `INVOICE`). No se genera un aviso de aceptación o cancelación mientras el movimiento de stock siga pendiente o haya sido rechazado.
- El publicador de Orders exige confirmación del broker y que exista una cola de destino. Si falla, conserva el registro y reintenta con espera creciente hasta `ORDERS_NOTIFICATIONS_MAX_ATTEMPTS` (20). Reiniciar Orders no borra los comandos pendientes.
- Notify usa ACK manual: confirma después de que SMTP acepta el correo; un duplicado (`eventId` ya procesado) se confirma sin reenviar; un mensaje inválido se rechaza de inmediato hacia la DLQ; un error de SMTP se reintenta 3 veces con espera 1 s -> 2 s -> 4 s y, si persiste, va a `q.cmd.email.dlq`. Nunca se reencola un mensaje rechazado, para evitar bucles.
- Una caída de RabbitMQ no impide crear o cambiar un pedido: la salud de Orders depende de su base. RabbitMQ y SMTP sí forman parte de la readiness de Notify.

`ORDERS_NOTIFICATIONS_ENABLED=false` desactiva solamente la publicación; los comandos se siguen guardando y se envían cuando se habilita.

## Levantar en Docker

Agregar a tu `.env` local, sin subirlo a Git:

```dotenv
RABBITMQ_USERNAME=pedidos360_local
RABBITMQ_PASSWORD=<define-una-clave-local>
RABBITMQ_VHOST=pedidos360
RABBITMQ_PORT=5672
RABBITMQ_MANAGEMENT_PORT=15672
NOTIFY_PORT=8083
MAILPIT_PORT=8025
MAILPIT_SMTP_PORT=1025
NOTIFY_EMAIL_FROM=no-reply@pedidos360.test
NOTIFY_EMAIL_RECIPIENT=demo@pedidos360.test
MQ_ADMIN_PORT=8086
```

El broker ahora vive en `compose.rabbitmq.yml` (junto con mq-admin); `compose.notify.yml` aporta Mailpit, Notify y la configuración de publicación de Orders y **debe usarse junto a** `compose.rabbitmq.yml`:

```powershell
Set-Location -LiteralPath 'D:\Cloud_Native_EVA'
docker compose -f compose.postgres.yml -f compose.catalog.yml -f compose.orders.yml -f compose.rabbitmq.yml -f compose.notify.yml up -d --build
docker compose -f compose.postgres.yml -f compose.catalog.yml -f compose.orders.yml -f compose.rabbitmq.yml -f compose.notify.yml ps
Invoke-RestMethod http://localhost:8083/actuator/health/readiness
```

Se levantan nueve contenedores: BFF, Orders, Catalog, dos PostgreSQL, RabbitMQ, mq-admin, Notify y Mailpit. Para la solución completa (Kafka, Audit, Report y los consumidores de tickets y boletas) usar el comando de [RabbitMQ en Pedidos360](RABBITMQ.md#10-levantar-con-docker-compose).

| Dirección local | Uso |
|---|---|
| `http://localhost:8025` | Ver correos en Mailpit |
| `http://localhost:15672` | Consola de RabbitMQ con el usuario local |
| `http://localhost:8086` | mq-admin (API REST, rol ADMIN) |
| `http://localhost:8083/actuator/health/readiness` | RabbitMQ y SMTP desde Notify |

Los puertos están limitados a `127.0.0.1`. Notify no tiene un endpoint HTTP para enviar correos y deniega las rutas ajenas a health. Detener sin borrar datos: el mismo comando con `stop`; no usar `down -v` en desarrollo.

Si el volumen de RabbitMQ viene de la versión anterior, `q.cmd.email` y `q.cmd.email.dlq` tienen otros argumentos y RabbitMQ responde `406 PRECONDITION_FAILED`: ver la [nota de migración](RABBITMQ.md#12-nota-de-migración-volumen-rabbitmq_data-existente).

## Prueba manual

1. Abrir Mailpit y comprobar que Notify esté `UP`.
2. Con la colección [Orders](../postman/Pedidos360-Orders.postman_collection.json) y tokens reales, crear un pedido desde el BFF y revisar el correo `CREADO`.
3. Aceptar el pedido como OPERADOR o ADMIN (requiere la identidad técnica de Orders) y revisar `ACEPTADO`. Repetir la aceptación no genera otro aviso. Cancelar un pedido propio antes de preparación y revisar `CANCELADO`.
4. Si un correo falla, revisar `q.cmd.email.dlq` con `GET /api/v1/mq-admin/dead-letter-queues` o en la consola de RabbitMQ. No borrar mensajes para ocultar errores.

Para recuperar mensajes de la DLQ, resolver primero la causa (por ejemplo, SMTP caído) y usar `POST /api/v1/mq-admin/dead-letter-queues/q.cmd.email.dlq/replay` con `{"maxMessages":10}`: reenvía cada mensaje a su exchange y routing key originales y lo confirma en la DLQ solo cuando el broker aceptó la republicación. Puede repetirse un correo si Notify ya lo había enviado; el `eventId` permite reconocerlo.

## Pruebas automatizadas

Con Java 21 o superior, Node 22+ y Docker:

```powershell
Set-Location -LiteralPath 'D:\Cloud_Native_EVA\ms-pedidos360-orders'
.\mvnw.cmd clean verify -Ppostgres-it
Set-Location -LiteralPath 'D:\Cloud_Native_EVA\ms-pedidos360-notify'
.\mvnw.cmd clean verify
Set-Location -LiteralPath 'D:\Cloud_Native_EVA'
node scripts/test-orders-flow.mjs --notify
```

Compilar también BFF y Catalog con `clean verify` si todavía no tienen sus JAR en `target`. La opción `--notify` usa PostgreSQL, RabbitMQ y Mailpit temporales, JAR reales y JWT firmados por un emisor local. Comprueba pedidos, entrega SMTP, caída de RabbitMQ, reinicio de Orders, mensajes inválidos y recuperación de un fallo SMTP desde la DLQ; los correos esperados se cuentan solo con los comandos `EMAIL` y `EMAIL_PRIORITY` del outbox. Al terminar elimina solamente sus recursos temporales.

Para comprobar la construcción de imágenes y el arranque con puertos aleatorios y volúmenes temporales:

```powershell
node scripts/test-notify-compose.mjs
```

Usa `compose.postgres.yml`, `compose.catalog.yml`, `compose.orders.yml`, `compose.rabbitmq.yml` y `compose.notify.yml` (nueve servicios, incluido mq-admin), verifica las interfaces locales, la salud de los servicios Java y su ejecución con usuario `10001`, y al terminar retira su proyecto y sus volúmenes temporales.

## Límites

- La entrega es al menos una vez. Si SMTP acepta el correo y Notify cae antes de confirmar, puede repetirse; Notify deduplica por `eventId` en memoria (se pierde al reiniciar).
- Los reintentos pueden cambiar el orden de llegada; cada aviso conserva el estado y la fecha de su evento.
- El destinatario es una dirección de demostración. Para producción hay que resolverlo desde una fuente confiable y configurar SMTP autenticado con TLS (`SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS`).
- Se usa un único broker local. Alta disponibilidad, usuarios separados por servicio y permisos mínimos deben revisarse antes de AWS.

## Referencias técnicas

[AMQP de Spring Boot](https://docs.spring.io/spring-boot/reference/messaging/amqp.html), [confirmaciones y rechazo en RabbitMQ](https://www.rabbitmq.com/docs/confirms), [dead lettering](https://www.rabbitmq.com/docs/dlx) e [imagen Docker de Mailpit](https://mailpit.axllent.org/docs/install/docker/).
