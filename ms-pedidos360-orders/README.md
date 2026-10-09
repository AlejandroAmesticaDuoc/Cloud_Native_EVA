# MS Pedidos360 Orders

Microservicio de pedidos con Spring Boot 4.1.1, Java 21, PostgreSQL, Flyway y JWT. Mantiene las rutas y DTO que utiliza el BFF.

## Implementado

- Crear pedidos con el cliente obtenido del token y los precios consultados en Catalog.
- Listar y consultar pedidos, aplicando permisos y propiedad también dentro de Orders.
- Validar transiciones de estado y cancelaciones.
- Descontar stock al aceptar y devolverlo al cancelar un pedido aceptado.
- Conservar precios históricos y calcular totales con `BigDecimal`.
- Guardar operaciones pendientes para poder reintentar después de una respuesta perdida o un reinicio.
- Autenticación de servicio con OAuth2 client credentials para modificar stock.
- Salud, OpenAPI opcional, trazabilidad, errores controlados, pruebas y Dockerfile sin root.

## Carpetas

```text
src/main/java/cl/duoc/pedidos360/orders/
  client/       Comunicación HTTP con Catalog
  config/       Seguridad, cliente HTTP, OpenAPI, trazabilidad y topología RabbitMQ
  controller/   Rutas de pedidos
  dto/          Solicitudes, respuestas y estados
  entity/       Pedido y detalle persistente
  exception/    Errores controlados
  messaging/    Outbox de comandos y eventos, publicadores RabbitMQ y Kafka
  repository/   Consultas y bloqueo por pedido
  security/     Usuario autenticado y credencial de servicio
  service/      Reglas de negocio, coordinación del stock y regla de comandos (OrderCommandPolicy)
src/main/resources/db/migration/
  V1__create_orders.sql
  V2__notification_outbox.sql
  V3__order_event_outbox.sql
  V4__command_outbox.sql
src/test/       API, JWT, OAuth2, HTTP y PostgreSQL real
```

## Ejecutar y probar

La [guía de Orders](../docs/ORDERS_COMPLETO.md) contiene las variables, la configuración de Entra y los comandos de Docker Compose. No se necesita instalar PostgreSQL directamente en Windows.

Desde esta carpeta:

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd clean verify -Ppostgres-it
.\mvnw.cmd clean verify -Prabbit-it
```

El primer comando usa H2 únicamente para tests. El segundo agrega PostgreSQL temporal y verifica el mismo contrato, el usuario SQL, la concurrencia y los reintentos de la outbox hasta `max-attempts`. El tercero (`OrdersRabbitIT`) agrega `rabbitmq:4.3.5-management-alpine`: verifica que cada comando llegue a su cola con el envelope común, el correo prioritario por `cmd.topic`, la falla ante un mensaje devuelto sin ruta y que los consumidores puedan redeclarar las colas sin `406 PRECONDITION_FAILED`. Docker debe estar iniciado.

Después de compilar los tres servicios, desde la raíz:

```powershell
node scripts/test-orders-flow.mjs
```

Este script prueba BFF → Orders → Catalog con JWT firmados y bases temporales. Incluye una respuesta perdida después de descontar stock y el reinicio de Orders. No usa cuentas reales ni modifica la base de desarrollo.

## RabbitMQ: comandos asíncronos

Orders es el productor de los tres comandos del [contrato RabbitMQ](../docs/RABBITMQ.md). Se activa con `ORDERS_NOTIFICATIONS_ENABLED=true`; con `false` no se crea ningún bean de RabbitMQ ni se abre conexión.

| Evento del pedido | Comandos | Exchange / routing key | Consumidor |
|---|---|---|---|
| Creación y cambios de estado | `EMAIL` | `cmd.direct` / `email.send` | notify |
| Cambio a `CANCELADO` | `EMAIL_PRIORITY` (en lugar de `EMAIL`) | `cmd.topic` / `email.send.high` | notify |
| Cambio a `ACEPTADO` | `EMAIL` + `KITCHEN_TICKET` | `cmd.direct` / `kitchen.ticket` | catalog |
| Cambio a `ENTREGADO` | `EMAIL` + `INVOICE` | `cmd.direct` / `invoice.gen` | report |

Piezas (negocio separado de la mensajería):

- `service/OrderCommandPolicy`: regla pura de la tabla anterior.
- `service/OrderCommands`: puerto que usa `OrdersService`; no conoce RabbitMQ.
- `messaging/CommandOutbox`: implementa el puerto; guarda una fila por comando en `notification_outbox` (columna `command_type`) en la misma transacción del pedido.
- `messaging/OutboxPublisher`: publica las filas pendientes en orden (`FOR UPDATE SKIP LOCKED`).
- `messaging/RabbitCommandPublisher`: resuelve exchange y routing key desde `MessagingProperties`, fija el envelope (`messageId`=eventId, `type`=routing key, `correlationId`=`order-<id>`, `timestamp`, `appId`, `x-trace-id`, `x-schema-version`) y espera el publisher confirm.
- `config/MessagingProperties`: claves `messaging.*` de `application.properties`, única fuente de nombres.
- `config/RabbitTopologyConfig`: 3 exchanges, 6 colas (principal + DLQ por ruta) y 9 bindings como `@Bean` individuales. Orders declara también las colas de los consumidores para que ningún comando se pierda aunque el consumidor no haya arrancado.
- `config/RabbitPublisherConfig`: registra en logs las devoluciones (`mandatory`) y los nack del broker.

Entrega y errores del productor:

- Éxito: el broker confirma (ack) y la fila queda con `published_at`.
- Nack (por ejemplo, cola llena con `x-overflow=reject-publish`), mensaje devuelto sin ruta o sin confirmación en 5 s: `WARN` y nuevo intento con espera exponencial hasta 60 s.
- Al agotar `ORDERS_NOTIFICATIONS_MAX_ATTEMPTS` (20 por defecto) la fila queda con `failed_at`, se registra `ERROR` con la excepción y no se reintenta más.
- Los rechazos de los consumidores terminan en `q.cmd.<ruta>.dlq` mediante `cmd.dead.dlx`.

Variables: `ORDERS_NOTIFICATIONS_ENABLED`, `ORDERS_NOTIFICATIONS_POLL_DELAY`, `ORDERS_NOTIFICATIONS_MAX_ATTEMPTS`, `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, `RABBITMQ_VHOST` (`pedidos360`), `RABBITMQ_EMAIL_QUEUE` y `RABBITMQ_EMAIL_DLQ`.

## Límites de este bloque

La creación de pedidos todavía no tiene clave de idempotencia: repetir POST puede crear otro pedido. Orders guarda avisos y eventos en tablas outbox para publicarlos en RabbitMQ y Kafka; ver [Notify](../docs/NOTIFY_RABBITMQ.md) y [Eventos](../docs/KAFKA_EVENTOS.md). La recuperación de movimientos de stock pendientes se activa al reintentar la misma acción; no existe un proceso automático de reconciliación de stock. Cobros, Audit y Report siguen fuera de este bloque.

La configuración y prueba real de Entra y el despliegue AWS siguen pendientes. No exponer Orders, Catalog ni sus bases directamente a Internet.
