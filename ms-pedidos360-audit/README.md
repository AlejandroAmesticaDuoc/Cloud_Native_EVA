# Pedidos360 Audit

Historial básico de pedidos: Kafka, PostgreSQL, consultas paginadas y autorización JWT para ADMIN y AUDITOR.

Ver [contrato, configuración, pruebas y límites](../docs/AUDIT_COMPLETO.md).

## RabbitMQ: auditoría de dead letters

Con `AUDIT_DEAD_LETTERS_ENABLED=true`, Audit registra cada mensaje que un consumidor de comandos rechaza (notify, catalog o report). Con `false` (por defecto) no se crea ningún bean de topología ni listener y no se abre conexión; la API HTTP no depende del broker (`management.health.rabbit.enabled=false`). El consumo Kafka existente no cambia.

### Por qué Audit escucha una copia

Cuando un consumidor hace `basicNack(tag, false, false)`, RabbitMQ envía el mensaje a `cmd.dead.dlx` (exchange direct) con la routing key de su ruta (`email.send`, `kitchen.ticket` o `invoice.gen`). Ese exchange tiene dos colas enlazadas a cada routing key:

```text
cmd.dead.dlx --email.send-->         q.cmd.email.dlq        (la DLQ conserva el mensaje para inspección y reproceso)
cmd.dead.dlx --email.send-->         q.audit.dead-letters   (copia para auditoría; igual con kitchen.ticket e invoice.gen)
cmd.dead.dlx --audit.dead-letters--> q.audit.dead-letters.dlq (rechazos de la propia auditoría)
```

Audit no consume las DLQ: si lo hiciera, el mensaje desaparecería de ellas y `mq-admin` ya no podría reprocesarlo. Con la copia, la DLQ sigue intacta (retención 7 días, máx. 10000) y Audit guarda un historial permanente en PostgreSQL, aunque la DLQ se purgue o el mensaje expire. Las DLQ y la cola de auditoría son independientes: un fallo de Audit no bloquea ni altera el rechazo original.

### Piezas

```text
config/MessagingProperties          messaging.exchanges.dead-letter y messaging.dead-letters.* (única fuente de nombres)
config/RabbitTopologyConfig         cmd.dead.dlx, q.audit.dead-letters, q.audit.dead-letters.dlq y 4 bindings
config/RabbitListenerConfig         manualAckContainerFactory (ACK manual, prefetch 1, sin requeue ni retry interceptor)
messaging/deadletter/DeadLetterMapper    Extrae x-death (más reciente primero) y x-first-death-*
messaging/deadletter/DeadLetterListener  Consumidor de q.audit.dead-letters
messaging/support/ManualAckHandler       Tabla de decisiones ACK/NACK
service/DeadLetterStore             Inserción idempotente en audit_dead_letters y consulta paginada
controller/DeadLetterController     GET /api/v1/audit/dead-letters?afterId=0&size=50
src/main/resources/db/migration/V2__create_dead_letters.sql
```

Se registra: cola de origen, motivo (`rejected`, ...), conteo de muertes, exchange y routing key originales, `messageId`, `type`, `correlationId`, `x-trace-id`, la hora de la muerte y el payload. Si el payload supera 65536 bytes o no es texto UTF-8 solo se guardan su SHA-256 y su tamaño; sin `messageId` se usa `sha256:<hash>`. La clave única `(message_id, source_queue, death_count)` evita duplicados ante reentregas, pero registra un nuevo rechazo del mismo mensaje tras un reproceso. Cada registro escribe `WARN [DLQ] dead letter auditada ...`.

| Situación | Acción | Log |
|---|---|---|
| Registrada | `basicAck` | WARN `[DLQ] dead letter auditada` |
| Ya registrada (mismo mensaje, cola y conteo) | `basicAck` | INFO |
| Sin `x-death` ni `x-first-death-queue` (no es una dead letter) | `basicNack(tag, false, false)` inmediato -> `q.audit.dead-letters.dlq` | WARN `[DLQ]` |
| Error de base u otro transitorio | 3 intentos (espera 1 s, 2 s); agotados: `basicNack(tag, false, false)` -> `q.audit.dead-letters.dlq` | WARN `[RETRY]`; ERROR `[DLQ]` |

Nunca se usa `requeue=true`. La consulta exige scope `pedidos360.access` y rol ADMIN o AUDITOR; pagina por cursor igual que `/api/v1/audit` (`nextAfterId`).

Variables: `AUDIT_DEAD_LETTERS_ENABLED`, `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` y `RABBITMQ_VHOST` (`pedidos360`).

## Pruebas

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd clean verify -Ppostgres-it
.\mvnw.cmd clean verify -Prabbit-it
```

El primero no necesita Docker (parser Kafka, extracción de `x-death`, decisiones ACK/NACK con `Channel` simulado y topología). El segundo agrega `AuditPostgresIT` con PostgreSQL temporal. El tercero agrega `AuditDeadLetterRabbitIT`: PostgreSQL y `rabbitmq:4.3.5-management-alpine` reales; un comando rechazado en `q.cmd.email` llega a `q.cmd.email.dlq` y queda auditado, y un mensaje que no es dead letter termina en `q.audit.dead-letters.dlq`.
