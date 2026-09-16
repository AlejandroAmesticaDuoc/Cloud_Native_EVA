# Notify Service

Microservicio de notificaciones de Pedidos360. Recibe comandos JSON desde RabbitMQ y envía avisos por SMTP a un destinatario de demostración configurable. No recibe llamadas del BFF ni se conecta a las bases de otros servicios.

## Estructura

```text
src/main/java/cl/duoc/pedidos360/notify/
  config/       Colas, destinatario y seguridad HTTP
  dto/          Contrato de la notificación
  messaging/    Consumidor RabbitMQ y validaciones
  service/      Construcción y envío del correo
src/main/resources/application.properties
src/test/       Contrato, envío, fallos y rutas de health
```

Java 21, Spring Boot 4.1.1, Spring AMQP, Spring Mail y validaciones Jakarta. La persistencia de mensajes la mantiene RabbitMQ; Notify no tiene base de datos propia en el alcance básico.

## Compilar y probar

```powershell
.\mvnw.cmd clean verify
```

Las pruebas Maven no envían correos ni necesitan RabbitMQ. Desde la raíz, después de compilar BFF, Orders, Catalog y Notify:

```powershell
node scripts/test-orders-flow.mjs --notify
```

Ese comando utiliza un broker y un SMTP locales reales, temporales y aislados.

## Ejecutar

Seguir la [guía de RabbitMQ y Notify](../docs/NOTIFY_RABBITMQ.md) para variables, Docker Compose, pruebas manuales y límites. Spring Boot no carga `.env` automáticamente: ese archivo es para Compose; con `spring-boot:run`, exportar las variables en la misma terminal.

Puerto local `8083`. Solo se permite consultar `/actuator/health` y sus grupos. El consumo usa `q.cmd.email`; los mensajes que no se pueden procesar terminan en `q.cmd.email.dlq` después de los reintentos.

Se confirma cuando SMTP acepta el correo, no cuando una persona lo lee. Puede haber duplicados ante fallos entre la entrega SMTP y la confirmación a RabbitMQ. En local se utiliza Mailpit; no se envían correos reales.
