# Notificaciones con RabbitMQ

## Qué implementamos

Orders genera un aviso al crear un pedido y después de cada cambio efectivo de estado. Notify recibe el aviso y envía un correo por SMTP. Repetir una aceptación o cancelación ya completada no genera otro aviso.

```text
Orders -> PostgreSQL de Orders (pedido + aviso pendiente)
                       |
                       v
                    RabbitMQ -> q.cmd.email -> Notify -> Mailpit
                                                   |
                                                   v
                                             q.cmd.email.dlq
```

Mailpit captura los correos para verlos en el navegador. La configuración local no envía correos a Internet. Como todavía no tenemos un directorio de correos de clientes, todos los avisos llegan a `NOTIFY_EMAIL_RECIPIENT`, una dirección de demostración configurable. No interpretamos `customerId` como correo ni aceptamos un destinatario enviado por el frontend.

Este bloque no modifica las rutas públicas ni requiere cambios en BFF o Angular.

## Contrato del mensaje

JSON con `Content-Type: application/json`, entrega persistente y `message_id` igual a `eventId`:

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

Estados: `CREADO`, `ACEPTADO`, `EN_PREPARACION`, `DESPACHADO`, `ENTREGADO` y `CANCELADO`. Notify rechaza campos desconocidos, versiones diferentes, identificadores inválidos, estados desconocidos y mensajes mayores a 8192 bytes. No se incluyen Access Tokens, contraseñas, precios ni direcciones entregadas por el usuario.

## Fallos y reintentos

- La migración `V2__notification_outbox.sql` agrega una tabla solo en la base de Orders. No cambia V1 ni elimina pedidos existentes.
- El pedido y su aviso se guardan en la misma transacción. Si falla, ambos cambios se revierten.
- No se genera un aviso de aceptación o cancelación mientras el movimiento de stock siga pendiente o haya sido rechazado.
- El publicador procesa un aviso por ciclo, exige confirmación del broker y comprueba que exista una cola de destino. Si falla, conserva el registro y reintenta con esperas de 2, 4, 8 segundos, hasta un máximo de 60 segundos entre intentos.
- Reiniciar Orders no borra los avisos pendientes. El bloqueo de filas evita que dos publicadores trabajen simultáneamente sobre el mismo registro.
- Notify confirma el consumo después de que SMTP acepta el correo. Ante un error realiza hasta dos reintentos adicionales; después rechaza el mensaje y RabbitMQ lo envía a `q.cmd.email.dlq`. Los comandos inválidos tampoco se reencolan indefinidamente.
- Una caída de RabbitMQ no impide crear o cambiar un pedido. La salud de Orders depende de su base, no de que el envío asíncrono esté al día. RabbitMQ y SMTP sí forman parte de la salud de Notify.

`ORDERS_NOTIFICATIONS_ENABLED=false` desactiva solamente la publicación. Los avisos se siguen guardando y se enviarán cuando se habilite. No se generan avisos retroactivos para pedidos anteriores a esta implementación.

## Levantar todo en Docker

Usar Docker Desktop con contenedores Linux, la rama `feat/services-aws-integration` y la configuración existente de PostgreSQL y Entra de [Orders](ORDERS_COMPLETO.md).

Agregar a tu `.env` local, sin subirlo a Git:

```dotenv
RABBITMQ_USERNAME=pedidos360_local
RABBITMQ_PASSWORD=<define-una-clave-local>
RABBITMQ_VHOST=pedidos360
RABBITMQ_PORT=5672
RABBITMQ_MANAGEMENT_PORT=15672
RABBITMQ_EMAIL_QUEUE=q.cmd.email
RABBITMQ_EMAIL_DLQ=q.cmd.email.dlq
NOTIFY_PORT=8083
MAILPIT_PORT=8025
MAILPIT_SMTP_PORT=1025
NOTIFY_EMAIL_FROM=no-reply@pedidos360.test
NOTIFY_EMAIL_RECIPIENT=demo@pedidos360.test
```

Reemplazar el marcador de contraseña. Las variables de PowerShell tienen prioridad sobre `.env` en Compose. Cambiar las credenciales de RabbitMQ en `.env` no cambia automáticamente el usuario de un volumen existente.

Desde la raíz:

```powershell
Set-Location -LiteralPath 'D:\Cloud_Native_EVA'
docker compose -f compose.postgres.yml -f compose.catalog.yml -f compose.orders.yml -f compose.notify.yml up -d --build
docker compose -f compose.postgres.yml -f compose.catalog.yml -f compose.orders.yml -f compose.notify.yml ps
Invoke-RestMethod http://localhost:8083/actuator/health/readiness
```

Esperar `UP` antes de probar. Se levantan ocho contenedores: BFF, Orders, Catalog, dos PostgreSQL, RabbitMQ, Notify y Mailpit. El complemento habilita la publicación de Orders y utiliza los nombres internos de Docker para RabbitMQ y SMTP.

| Dirección local | Uso |
|---|---|
| `http://localhost:8025` | Ver correos en Mailpit. |
| `http://localhost:15672` | Administrar las colas con el usuario local de RabbitMQ. |
| `http://localhost:8083/actuator/health/readiness` | Revisar RabbitMQ y SMTP desde Notify. |

Los puertos publicados están limitados a `127.0.0.1`. No abrirlos en AWS tal como están. Notify no tiene un endpoint HTTP para enviar correos y deniega las rutas ajenas a health.

Detener sin borrar datos:

```powershell
docker compose -f compose.postgres.yml -f compose.catalog.yml -f compose.orders.yml -f compose.notify.yml stop
```

No usar `down -v` en desarrollo. Los volúmenes mantienen bases, colas y buzón.

## Prueba manual

1. Abrir Mailpit y comprobar que Notify esté `UP`.
2. Usar la colección [Orders](../postman/Pedidos360-Orders.postman_collection.json) con tokens reales. Crear un pedido desde el BFF como CLIENTE u OPERADOR.
3. Revisar el correo de estado `CREADO`.
4. Aceptar el pedido como OPERADOR o ADMIN. Esto requiere la identidad técnica de Orders configurada en Entra. Revisar `ACEPTADO`.
5. Repetir la aceptación: no debe generar otro aviso. Cancelar un pedido propio antes de preparación y revisar `CANCELADO`.
6. Consultar `q.cmd.email.dlq` en RabbitMQ si un correo falla. No borrar mensajes para ocultar errores.

Para recuperar un mensaje de la DLQ: resolver primero el problema, revisar el contenido y volver a publicarlo en `q.cmd.email` conservando `eventId`, JSON y `content_type=application/json`. Hacerlo de forma controlada: puede duplicarse un correo. No hay una tarea automática que vacíe la DLQ.

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

Compilar también BFF y Catalog con `clean verify` si todavía no tienen sus JAR en `target`. La opción `--notify` usa PostgreSQL, RabbitMQ y Mailpit temporales, JAR reales y JWT firmados por un emisor local de pruebas. No usa credenciales cloud ni la base de desarrollo. Comprueba pedidos, entrega SMTP, caída de RabbitMQ, reinicio de Orders, mensajes inválidos y recuperación de un fallo SMTP desde la DLQ. Al terminar elimina solamente sus recursos temporales.

Resultado verificado: Orders pasó 88 pruebas rápidas y 56 con PostgreSQL; Notify pasó 22 pruebas. El flujo con `--notify` pasó 66 comprobaciones de pedidos y 17 de mensajería y SMTP.

Para comprobar además la construcción de imágenes y el arranque de los ocho contenedores con puertos aleatorios y volúmenes temporales independientes:

```powershell
node scripts/test-notify-compose.mjs
```

Esta prueba utiliza los cuatro archivos Compose del proyecto. Verifica las interfaces locales, la salud de los cuatro servicios Java y su ejecución con usuario `10001`. Al terminar retira su proyecto Compose y sus volúmenes temporales; no altera los de desarrollo. La prueba de health no sustituye a la integración con tokens reales de Entra.

## Límites del alcance básico

- La entrega es al menos una vez, no exactamente una vez. Si SMTP acepta el correo y Notify cae antes de confirmar, puede repetirse. También puede repetirse una publicación si Orders cae antes de registrar la confirmación del broker. `eventId` permite reconocer esos casos, pero todavía no hay deduplicación persistente en Notify.
- Los reintentos pueden cambiar el orden de llegada. Cada aviso conserva el estado y la fecha de su evento.
- La tabla outbox conserva los registros publicados. La limpieza por antigüedad y las alertas de acumulación quedan para después.
- El destinatario es una dirección de demostración, no el correo individual de cada cliente. Para producción hay que resolverlo desde una fuente confiable y configurar SMTP autenticado con TLS. Notify admite `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH` y `SMTP_STARTTLS`; el Compose local fija Mailpit intencionalmente.
- Se usa un único broker local. Alta disponibilidad, usuarios separados por servicio y permisos mínimos deben revisarse antes de AWS. Las colas se declaran en código para mantener simple esta etapa; no cambiar sus nombres o argumentos con mensajes pendientes sin planificar una migración.
- Kafka y Audit se agregaron en bloques posteriores; ver [Eventos Kafka](KAFKA_EVENTOS.md) y [Audit](AUDIT_COMPLETO.md). Report y la integración con frontend, Entra real y AWS siguen pendientes.

## Referencias técnicas

Configuración basada en [AMQP de Spring Boot](https://docs.spring.io/spring-boot/reference/messaging/amqp.html), [confirmaciones de Spring AMQP](https://docs.spring.io/spring-amqp/docs/current/api/org/springframework/amqp/rabbit/connection/CorrelationData.html) y [confirmación y rechazo de RabbitMQ](https://www.rabbitmq.com/docs/confirms). El buzón usa la [imagen Docker de Mailpit](https://mailpit.axllent.org/docs/install/docker/).
