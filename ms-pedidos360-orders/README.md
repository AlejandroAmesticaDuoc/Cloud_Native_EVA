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
  config/       Seguridad, cliente HTTP, OpenAPI y trazabilidad
  controller/   Rutas de pedidos
  dto/          Solicitudes, respuestas y estados
  entity/       Pedido y detalle persistente
  exception/    Errores controlados
  messaging/    Avisos y eventos persistentes, publicadores RabbitMQ y Kafka
  repository/   Consultas y bloqueo por pedido
  security/     Usuario autenticado y credencial de servicio
  service/      Reglas de negocio y coordinación del stock
src/main/resources/db/migration/
  V1__create_orders.sql
  V2__notification_outbox.sql
  V3__order_event_outbox.sql
src/test/       API, JWT, OAuth2, HTTP y PostgreSQL real
```

## Ejecutar y probar

La [guía de Orders](../docs/ORDERS_COMPLETO.md) contiene las variables, la configuración de Entra y los comandos de Docker Compose. No se necesita instalar PostgreSQL directamente en Windows.

Desde esta carpeta:

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd clean verify -Ppostgres-it
```

El primer comando usa H2 únicamente para tests. El segundo agrega PostgreSQL temporal y verifica el mismo contrato, el usuario SQL y la concurrencia. Docker debe estar iniciado.

Después de compilar los tres servicios, desde la raíz:

```powershell
node scripts/test-orders-flow.mjs
```

Este script prueba BFF → Orders → Catalog con JWT firmados y bases temporales. Incluye una respuesta perdida después de descontar stock y el reinicio de Orders. No usa cuentas reales ni modifica la base de desarrollo.

## Límites de este bloque

La creación de pedidos todavía no tiene clave de idempotencia: repetir POST puede crear otro pedido. Orders guarda avisos y eventos en tablas outbox para publicarlos en RabbitMQ y Kafka; ver [Notify](../docs/NOTIFY_RABBITMQ.md) y [Eventos](../docs/KAFKA_EVENTOS.md). La recuperación de movimientos de stock pendientes se activa al reintentar la misma acción; no existe un proceso automático de reconciliación de stock. Cobros, Audit y Report siguen fuera de este bloque.

La configuración y prueba real de Entra y el despliegue AWS siguen pendientes. No exponer Orders, Catalog ni sus bases directamente a Internet.
