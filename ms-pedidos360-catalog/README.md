# MS Pedidos360 Catalog

Microservicio de catálogo de productos. Utiliza Spring Boot 4.1.1, Java 21 como objetivo de compilación, Spring Data JPA y PostgreSQL.

## Estado actual

Implementado:

- DTO compatibles con el contrato del BFF y validaciones de entrada.
- Endpoint de salud en el puerto 8082.
- Configuración de conexión PostgreSQL mediante variables de entorno.
- Migraciones Flyway para productos y descuentos de stock por pedido.
- Entidad `Product` y repositorio `ProductRepository`.
- Consultas de productos activos, desactivación lógica y versión para detectar actualizaciones concurrentes.
- Pruebas rápidas con H2 y contrato de integración con PostgreSQL real mediante Testcontainers.
- CRUD completo, stock transaccional e idempotente por pedido y errores controlados.
- JWT con firma, issuer, audience, expiración obligatoria, scope y roles.
- `X-Trace-Id`, OpenAPI opcional y Dockerfile con Java 21, sin ejecutar como root.
- Consumidor RabbitMQ del ticket de cocina (`q.cmd.kitchen`) con ACK manual y DLQ; ver [RabbitMQ](#rabbitmq-ticket-de-cocina).

La integración local BFF → Orders → Catalog → PostgreSQL está probada. Orders ya coordina los estados y movimientos de stock; ver la [guía de Orders](../docs/ORDERS_COMPLETO.md). Queda pendiente la validación con frontend, Entra real y AWS. Los microservicios y sus bases deben mantenerse en una red privada en el despliegue.

## Estructura

```text
src/main/java/cl/duoc/pedidos360/catalog/
  config/               Seguridad, OpenAPI, trazabilidad y RabbitMQ (topología, listener, propiedades)
  controller/           API pública, stock interno y tickets de cocina
  dto/                  Contrato de entrada y salida (incluye KitchenTicketCommand)
  entity/               Productos, descuentos por pedido y tickets de cocina
  exception/            Errores controlados
  messaging/kitchen/    KitchenTicketListener: consumidor de q.cmd.kitchen
  messaging/support/    ManualAckHandler (ACK/NACK) y lectura estricta de JSON
  repository/           Consultas JPA y bloqueos de filas
  security/             Respuestas 401 y 403
  service/              Reglas de catálogo, stock y ticket de cocina
src/main/resources/
  application.properties
  db/migration/V1__create_products.sql
  db/migration/V2__create_stock_deductions.sql
  db/migration/V3__create_kitchen_tickets.sql
src/test/java/cl/duoc/pedidos360/catalog/
  config/               Salud y OpenAPI
  controller/           API, transacciones y concurrencia
  dto/                  Pruebas de validación
  repository/           Contrato de persistencia en H2 y PostgreSQL
  security/             Validación real de JWT firmados
src/test/resources/
  application-test.properties
```

## Ejecutar localmente

Seguir primero la [guía de PostgreSQL local](../docs/POSTGRESQL_LOCAL.md). Allí se explica cómo iniciar Docker, definir las contraseñas y levantar la base.

El [contrato de Catalog](../docs/CATALOG_COMPLETO.md) explica rutas, permisos, Postman y ejecución conjunta con el BFF.

Catalog necesita estas variables en la misma terminal donde se inicia Maven:

| Variable | Valor predeterminado / uso |
|---|---|
| `CATALOG_PORT` | `8082` |
| `CATALOG_BIND_ADDRESS` | `127.0.0.1`; Docker usa `0.0.0.0` dentro del contenedor |
| `CATALOG_DB_URL` | `jdbc:postgresql://localhost:5432/pedidos360_catalog` |
| `CATALOG_DB_USERNAME` | `pedidos360_catalog` |
| `CATALOG_DB_PASSWORD` | Obligatoria, sin valor predeterminado |
| `JWT_ISSUER_URI` | Emisor del tenant, igual que en el BFF |
| `JWT_AUDIENCE` | Audiencia de la API, igual que en el BFF |
| `ORDERS_SERVICE_CLIENT_ID` | Identidad técnica permitida para stock; vacío deshabilita ese acceso |
| `API_DOCS_ENABLED` | `false` por defecto |
| `CATALOG_COMMANDS_ENABLED` | `false` por defecto; `true` activa el consumidor RabbitMQ de tickets de cocina |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, `RABBITMQ_VHOST` | Conexión al broker (`localhost`, `5672`, sin credenciales por defecto, `pedidos360`) |

Configurar los valores JWT reales antes de usar la API. Los predeterminados son referencias para arrancar, no una configuración de identidad utilizable. No existe un modo de desarrollo que permita saltarse la seguridad.

```powershell
Set-Location -LiteralPath 'D:\Cloud_Native_EVA\ms-pedidos360-catalog'
.\mvnw.cmd spring-boot:run
```

El archivo `.env.example` no se carga automáticamente al ejecutar Maven. No escribir credenciales en `application.properties` ni en los scripts SQL.

Salud: `GET http://localhost:8082/actuator/health`.
Disponibilidad con base de datos: `GET http://localhost:8082/actuator/health/readiness`.

## Persistencia

Cada producto tiene `id`, `name`, `price`, `stock`, `active` y una `version` interna. La versión no cambia el DTO público.

El precio se guarda como `NUMERIC(12,2)`, con hasta diez dígitos enteros y dos decimales. El stock no puede ser negativo. Desactivar cambia `active` a `false`; no elimina la fila.

Flyway es el único encargado de crear y modificar tablas. Hibernate tiene `ddl-auto=validate`: comprueba el esquema, pero no lo modifica automáticamente. Las nuevas modificaciones del esquema deben agregarse como nuevas migraciones.

## RabbitMQ: ticket de cocina

Cuando Orders acepta un pedido publica `kitchen.ticket` en `cmd.direct`. Con `CATALOG_COMMANDS_ENABLED=true`, Catalog lo consume y genera un ticket con los nombres de producto de su base (`Producto #id` si el producto ya no existe). Con el interruptor en `false` no se crea ningún bean de topología ni listener y no se abre conexión; la API HTTP nunca depende del broker (`management.health.rabbit.enabled=false`).

- Nombres en `application.properties` (`messaging.*`) enlazados a `config/MessagingProperties`; ninguna clase escribe nombres de colas o exchanges.
- `config/RabbitTopologyConfig`: `cmd.direct`, `cmd.topic`, `cmd.dead.dlx`, `q.cmd.kitchen` (DLX `cmd.dead.dlx`, routing key `kitchen.ticket`, máx. 10000, `reject-publish`), `q.cmd.kitchen.dlq` (7 días, máx. 10000) y sus bindings `cmd.direct[kitchen.ticket]`, `cmd.topic[kitchen.#]` y `cmd.dead.dlx[kitchen.ticket]`.
- `config/RabbitListenerConfig`: `manualAckContainerFactory` con ACK manual, prefetch 1, sin requeue y sin retry interceptor.
- `messaging/kitchen/KitchenTicketListener` valida el mensaje y delega en `service/KitchenTicketService` (negocio sin RabbitMQ), que guarda el ticket en `kitchen_tickets`.

| Situación | Acción | Log |
|---|---|---|
| Ticket creado | `basicAck` | INFO |
| `eventId` u `orderId` ya registrados | `basicAck` sin repetir efectos | INFO |
| Inválido (content-type, más de 32768 bytes, JSON, validación, `messageId` distinto del `eventId`) | `basicNack(tag, false, false)` inmediato -> `q.cmd.kitchen.dlq` | WARN `[DLQ]` |
| Error de base u otro transitorio | 3 intentos (espera 1 s, 2 s); agotados: `basicNack(tag, false, false)` -> DLQ | WARN `[RETRY]`; ERROR `[DLQ]` |

Nunca se usa `requeue=true`. Consulta: `GET /api/v1/catalog/kitchen-tickets/{orderId}` (scope `pedidos360.access` y rol ADMIN u OPERADOR; 404 si el pedido no tiene ticket).

## Pruebas

99 pruebas rápidas, sin Docker ni RabbitMQ:

```powershell
.\mvnw.cmd clean test
```

Las 99 anteriores y 47 adicionales con PostgreSQL temporal, requiere Docker:

```powershell
.\mvnw.cmd clean verify -Ppostgres-it
```

Consumo real del ticket de cocina con `rabbitmq:4.3.5-management-alpine` (Testcontainers, requiere Docker; `KitchenTicketRabbitIT`: ticket guardado y mensaje inválido en `q.cmd.kitchen.dlq` con `x-death` `reason=rejected`):

```powershell
.\mvnw.cmd clean verify -Prabbit-it
```

H2 está limitado a dependencias y recursos de test; no reemplaza PostgreSQL en la aplicación ni se incluye en el JAR final. La validación definitiva de compatibilidad con PostgreSQL es la segunda ejecución.

Para comprobar ambos servicios ejecutándose de verdad, compilar también el BFF con `clean verify` y ejecutar desde la raíz:

```powershell
node scripts/test-bff-catalog.mjs
```

Requiere Node 22+, Java y Docker. Realiza 26 comprobaciones con una base y un emisor de JWT temporales. No modifica datos de desarrollo ni sustituye la prueba final con Entra.

## Coordinación con el equipo

- Rama: `feat/services-aws-integration`.
- La persona encargada del BFF desarrolla este microservicio y PostgreSQL.
- Infraestructura coordina el despliegue y las redes en AWS.
- El BFF conserva sus DTO, rutas y reglas JWT actuales. No accede directamente a esta base.
