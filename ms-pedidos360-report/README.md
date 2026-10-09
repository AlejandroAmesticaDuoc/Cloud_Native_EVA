# Pedidos360 Report

KPIs de pedidos recibidos por Kafka: estados activos, monto entregado por hora y tiempos desde creación hasta entrega. Persistencia propia en PostgreSQL y consultas JWT solo para ADMIN.

Ver [contrato, configuración y pruebas](../docs/REPORT_COMPLETO.md).

## RabbitMQ: boleta de pedidos entregados

Cuando Orders marca un pedido como `ENTREGADO` publica `invoice.gen` en `cmd.direct`. Con `REPORT_COMMANDS_ENABLED=true`, Report lo consume y emite una boleta de demostración en `report_invoices`. Con el interruptor en `false` (por defecto) no se crea ningún bean de topología ni listener y no se abre conexión; la API HTTP no depende del broker (`management.health.rabbit.enabled=false`). El consumo Kafka existente no cambia.

```text
src/main/java/cl/duoc/pedidos360/report/
  config/MessagingProperties     Claves messaging.* (única fuente de nombres de colas y exchanges)
  config/RabbitTopologyConfig    cmd.direct, cmd.topic, cmd.dead.dlx, q.cmd.invoice, q.cmd.invoice.dlq y 3 bindings
  config/RabbitListenerConfig    manualAckContainerFactory (ACK manual, prefetch 1, sin requeue ni retry interceptor)
  messaging/invoice/             InvoiceCommandListener: consumidor de q.cmd.invoice
  messaging/support/             ManualAckHandler (ACK/NACK) y lectura estricta de JSON
  service/InvoiceService         Emisión idempotente de la boleta (sin RabbitMQ)
  service/InvoiceCalculator      Neto = total / 1,19 (2 decimales, HALF_UP); IVA = total - neto
  controller/InvoiceController   GET /api/v1/reports/invoices/{orderId}
src/main/resources/db/migration/V2__create_invoices.sql
```

Bindings: `cmd.direct[invoice.gen]` y `cmd.topic[invoice.#]` hacia `q.cmd.invoice`; `cmd.dead.dlx[invoice.gen]` hacia `q.cmd.invoice.dlq` (retención 7 días, máx. 10000).

| Situación | Acción | Log |
|---|---|---|
| Boleta emitida | `basicAck` | INFO |
| `eventId` u `orderId` ya emitidos | `basicAck` sin repetir efectos | INFO |
| Inválido (content-type, más de 32768 bytes, JSON, validación, `messageId` distinto del `eventId`) | `basicNack(tag, false, false)` inmediato -> `q.cmd.invoice.dlq` | WARN `[DLQ]` |
| La suma de `quantity * unitPrice` no coincide con `total` (regla de negocio permanente) | `basicNack(tag, false, false)` inmediato -> DLQ | WARN `[DLQ]` |
| Error de base u otro transitorio | 3 intentos (espera 1 s, 2 s); agotados: `basicNack(tag, false, false)` -> DLQ | WARN `[RETRY]`; ERROR `[DLQ]` |

Nunca se usa `requeue=true`. La consulta `GET /api/v1/reports/invoices/{orderId}` exige scope `pedidos360.access` y rol ADMIN; responde 404 si el pedido no tiene boleta.

Variables: `REPORT_COMMANDS_ENABLED`, `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` y `RABBITMQ_VHOST` (`pedidos360`).

## Pruebas

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd clean verify -Ppostgres-it
.\mvnw.cmd clean verify -Prabbit-it
```

El primero (59 pruebas) no necesita Docker: parser Kafka, cálculo de la boleta, parseo estricto del payload de ejemplo del contrato, topología y decisiones ACK/NACK con un `Channel` simulado. El segundo agrega `ReportPostgresIT` (32 pruebas) con PostgreSQL temporal, incluida la emisión idempotente y la consulta HTTP de la boleta. El tercero agrega `InvoiceRabbitIT` con PostgreSQL y `rabbitmq:4.3.5-management-alpine`: la boleta del ejemplo del contrato se emite y una boleta cuyos ítems no suman el total termina en `q.cmd.invoice.dlq` sin reintentos.
