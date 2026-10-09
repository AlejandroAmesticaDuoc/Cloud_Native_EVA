# Notify Service

Microservicio de notificaciones de Pedidos360. Recibe comandos JSON desde RabbitMQ y envía avisos por SMTP a un destinatario de demostración configurable. No recibe llamadas del BFF ni se conecta a las bases de otros servicios.

## Estructura

```text
src/main/java/cl/duoc/pedidos360/notify/
  config/               Topología RabbitMQ, fábrica de listeners con ACK manual, propiedades y seguridad HTTP
  dto/                  Contrato de la notificación (EmailCommand)
  messaging/email/      EmailCommandListener: consumidor de q.cmd.email
  messaging/support/    ManualAckHandler (ACK/NACK), lectura estricta de JSON e idempotencia en memoria
  service/              Construcción y envío del correo
src/main/resources/application.properties
src/test/       Contrato, ACK/NACK con Channel simulado, health y RabbitMQ real (NotifyRabbitIT)
```

Java 21, Spring Boot 4.1.1, Spring AMQP, Spring Mail y validaciones Jakarta. La persistencia de mensajes la mantiene RabbitMQ; Notify no tiene base de datos propia en el alcance básico.

## RabbitMQ

Nombres y política en `application.properties` (`messaging.*`), enlazados a `config/MessagingProperties`; ninguna clase escribe nombres de colas, exchanges ni routing keys.

`config/RabbitTopologyConfig` declara, como `@Bean` individuales: `cmd.direct`, `cmd.topic`, `cmd.dead.dlx`, `q.cmd.email` (DLX `cmd.dead.dlx`, routing key `email.send`, máx. 10000, `reject-publish`), `q.cmd.email.dlq` (retención 7 días, máx. 10000) y sus 3 bindings:

```text
cmd.direct   --email.send--> q.cmd.email      (correo normal)
cmd.topic    --email.#-->    q.cmd.email      (email.send.high: cancelaciones)
cmd.dead.dlx --email.send--> q.cmd.email.dlq  (rechazos)
```

`config/RabbitListenerConfig` crea `manualAckContainerFactory`: `AcknowledgeMode.MANUAL`, prefetch 1, `defaultRequeueRejected=false` y sin retry interceptor de Spring.

### Decisiones ACK/NACK (`messaging/support/ManualAckHandler`)

| Situación | Acción | Log |
|---|---|---|
| Correo aceptado por SMTP | `basicAck` | INFO `[ACK]` |
| `eventId` ya procesado | `basicAck` sin reenviar el correo | INFO `[ACK]` |
| Inválido: content-type, tamaño > 8192 bytes, JSON, validación, `messageId` distinto del `eventId` | `basicNack(tag, false, false)` inmediato -> `q.cmd.email.dlq` | WARN `[DLQ]` con cola, messageId y motivo |
| SMTP u otro error transitorio | 3 intentos con espera 1 s, 2 s (tope 4 s); agotados: `basicNack(tag, false, false)` -> DLQ | WARN `[RETRY]` por intento; ERROR `[DLQ]` con la excepción |
| Nunca | `requeue=true` | - |

Ninguna excepción escapa del listener. Cada log incluye `messageId`, `type`, `correlationId` y `x-trace-id`. La idempotencia es un registro LRU en memoria de 10000 `eventId` (se pierde al reiniciar; puede haber un duplicado si el proceso cae entre el envío SMTP y el ack).

## Compilar y probar

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd clean verify -Prabbit-it
```

El primero no necesita RabbitMQ ni envía correos. El segundo agrega `NotifyRabbitIT`: levanta `rabbitmq:4.3.5-management-alpine` con Testcontainers (requiere Docker), verifica la topología y sus argumentos, el procesamiento por `cmd.direct` y por `cmd.topic`, el envío de un mensaje inválido a `q.cmd.email.dlq` con `x-death` `reason=rejected` y el agotamiento de reintentos SMTP.

Desde la raíz, después de compilar BFF, Orders, Catalog y Notify:

```powershell
node scripts/test-orders-flow.mjs --notify
```

## Ejecutar

Seguir la [guía de RabbitMQ](../docs/RABBITMQ.md) y la [guía de Notify](../docs/NOTIFY_RABBITMQ.md) para variables, Docker Compose, pruebas manuales y límites. Spring Boot no carga `.env` automáticamente: ese archivo es para Compose; con `spring-boot:run`, exportar las variables en la misma terminal.

Puerto local `8083`. Solo se permite consultar `/actuator/health` y sus grupos; la disponibilidad incluye RabbitMQ y SMTP.

Se confirma cuando SMTP acepta el correo, no cuando una persona lo lee. En local se utiliza Mailpit; no se envían correos reales.
