# Eventos de pedidos con Kafka

## Alcance de este bloque

Orders publica eventos en `orders.events` para que después Audit registre la trazabilidad y Report genere estadísticas. No cambia el contrato HTTP del BFF ni requiere modificar Angular. Audit y Report todavía no están implementados.

Usamos Apache Kafka 4.3.1 en modo KRaft, sin ZooKeeper. Es un único broker local con tres particiones, factor de replicación 1 y retención de siete días. Esta configuración sirve para desarrollo y pruebas; no representa un despliegue seguro ni de alta disponibilidad para AWS.

```text
Pedido confirmado en Orders
  -> PostgreSQL: pedido + aviso RabbitMQ + evento Kafka
       |                            |
       v                            v
  RabbitMQ -> Notify          Kafka orders.events
                                   |
                                   +-> Audit (siguiente bloque)
                                   +-> Report (pendiente)
```

## Cuándo se publica cada evento

| Operación completada | Evento |
|---|---|
| Crear un pedido en estado CREADO | `OrderCreated` |
| Aceptar y confirmar el descuento de stock | `OrderAccepted` |
| Pasar a EN_PREPARACION, DESPACHADO o ENTREGADO | `OrderStatusChanged` |
| Cancelar, incluyendo devolución de stock cuando corresponde | `OrderCancelled` |

Cada cambio genera un solo evento. Por ejemplo, aceptar no genera además un `OrderStatusChanged`. Repetir el mismo estado o la misma cancelación no genera otro evento. Tampoco se publica un cambio rechazado, una operación sin permiso o un movimiento de stock que todavía está pendiente.

## Contrato v1

El valor es JSON y la clave Kafka es el identificador del pedido como texto, por ejemplo `15`. No enviamos objetos Java serializados ni headers con nombres de clases.

```json
{
  "schemaVersion": 1,
  "eventId": "656efca0-3b3a-423f-a8a0-7a1c21e89d6e",
  "eventType": "OrderAccepted",
  "orderId": 15,
  "aggregateVersion": 2,
  "occurredAt": "2026-09-12T16:00:00Z",
  "traceId": "pedido-demo-15",
  "actorId": "identificador-del-operador",
  "customerId": "identificador-del-cliente",
  "previousStatus": "CREADO",
  "status": "ACEPTADO",
  "createdAt": "2026-09-12T15:55:00Z",
  "total": 2501.00
}
```

El [JSON Schema](contracts/order-event-v1.schema.json) documenta los campos y las combinaciones de tipo y estado. `schemaVersion` identifica el formato del mensaje; `aggregateVersion` es un contador independiente por pedido que aumenta solo cuando hay un evento nuevo.

- `eventId` identifica el evento y no cambia al reintentar su publicación.
- `actorId` identifica al usuario autenticado que completó la acción; puede ser diferente del dueño del pedido.
- `customerId` es el dueño. Ambos identificadores vienen del backend y del JWT validado, no de campos nuevos enviados por Angular.
- `previousStatus` es `null` solo en la creación.
- `occurredAt` corresponde al cambio y `createdAt` conserva la fecha original del pedido. Ambas fechas están en UTC.
- `total` conserva el total del pedido en ese momento, usando los precios históricos almacenados en Orders. No representa un pago recibido.
- `traceId` conserva el seguimiento de la petición HTTP que completó la acción. Si se recupera una operación pendiente, se registra la petición que finalmente la completa.

No se incluyen tokens, contraseñas, correos ni credenciales cloud.

## Persistencia y recuperación

La migración V3 agrega `event_version` al pedido y la tabla `order_event_outbox`. No reemplaza V1 o V2 ni elimina datos. Los cambios del pedido, el aviso de correo y el evento Kafka se guardan en la misma transacción de PostgreSQL. La publicación a los brokers ocurre después, desde tareas separadas.

El publicador de Kafka:

1. Toma un evento pendiente y bloquea su fila.
2. Comprueba que no exista una versión anterior pendiente del mismo pedido.
3. Publica usando `orderId` como clave y espera la confirmación de Kafka.
4. Marca el registro como publicado; si falla, lo conserva y reintenta con esperas de 2, 4, 8 segundos, hasta un máximo de 60 segundos.

Dos publicadores no pueden adelantar la siguiente versión del mismo pedido mientras la anterior está bloqueada o esperando un reintento. Los eventos de otros pedidos pueden avanzar. Kafka y RabbitMQ tienen publicadores separados y dos hilos de planificación; una caída de Kafka no impide seguir enviando avisos a RabbitMQ.

Kafka usa productor idempotente y `acks=all`. Esto no convierte PostgreSQL y Kafka en una sola transacción: si Kafka confirma y Orders cae antes de guardar esa confirmación, el evento puede publicarse otra vez. La entrega es **al menos una vez** y los futuros consumidores deben deduplicar por `eventId`. Una republicación puede aparecer después de eventos más recientes: las proyecciones también deben verificar `aggregateVersion` para no retroceder su estado.

No se promete un orden global entre pedidos ni entre Kafka y RabbitMQ. No aumentar las particiones de un tópico con historial sin planificar cómo se conservará el procesamiento por pedido.

`ORDERS_EVENTS_ENABLED=false` desactiva la publicación, no el registro de eventos. Al habilitarla se envían los pendientes. Los pedidos anteriores a V3 empiezan con contador 0: su primer cambio posterior será la versión 1, pero no se inventará un evento histórico de creación. Para reconstruir datos anteriores se necesitará una carga inicial explícita, fuera de este bloque.

Los registros publicados del outbox todavía no se limpian automáticamente. Kafka sí elimina datos por retención; Audit deberá persistir el historial que necesite conservar más tiempo.

## Ejecutar con Docker

Mantener las variables locales de [Orders](ORDERS_COMPLETO.md) y [Notify](NOTIFY_RABBITMQ.md). Agregar a `.env`:

```dotenv
KAFKA_PORT=9092
KAFKA_CLUSTER_ID=cGVkaWRvczM2MC1rYWZrYQ
KAFKA_ORDERS_TOPIC=orders.events
```

`KAFKA_CLUSTER_ID` identifica el clúster local, no es una contraseña. No cambiarlo mientras se conserve el volumen de ese clúster.

Desde la raíz:

```powershell
Set-Location -LiteralPath 'D:\Cloud_Native_EVA'
docker compose -f compose.postgres.yml -f compose.catalog.yml -f compose.orders.yml -f compose.notify.yml -f compose.kafka.yml up -d --build
docker compose -f compose.postgres.yml -f compose.catalog.yml -f compose.orders.yml -f compose.notify.yml -f compose.kafka.yml ps -a
```

Hay nueve contenedores de ejecución continua y un inicializador. `kafka-init` crea `orders.events` si no existe y debe terminar con código `0`; no es un error que aparezca detenido. No modifica la configuración de un tópico que ya exista. Orders no crea tópicos automáticamente ni depende de que Kafka esté disponible para iniciar.

Dentro de Docker, Orders usa `kafka:19092`. Para ejecutar Orders desde Windows, exportar estas variables en su terminal:

```powershell
$env:ORDERS_EVENTS_ENABLED = 'true'
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
$env:KAFKA_ORDERS_TOPIC = 'orders.events'
```

Si cambias `KAFKA_PORT`, usa ese mismo puerto en `KAFKA_BOOTSTRAP_SERVERS` al ejecutar desde Windows. Spring Boot no carga `.env` automáticamente. Para levantar Kafka sin Notify, omitir únicamente `-f compose.notify.yml`.

Kafka solo publica su puerto externo en `127.0.0.1`. Los listeners internos no se publican al host. Esta configuración local utiliza PLAINTEXT, sin autenticación: antes de AWS hay que definir TLS, autenticación, permisos por servicio y acceso por red privada. No exponer `9092` a Internet.

## Ver los eventos

Después de crear o modificar pedidos mediante la colección Postman y tokens válidos:

```powershell
docker compose -f compose.postgres.yml -f compose.catalog.yml -f compose.orders.yml -f compose.notify.yml -f compose.kafka.yml exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:19092 --topic orders.events --from-beginning --property print.key=true --property print.partition=true
```

El consumidor queda esperando mensajes nuevos. Detenerlo con `Ctrl+C`; esto no detiene Kafka ni borra eventos. Es una inspección manual, no reemplaza a Audit o Report.

Detener los servicios sin borrar datos:

```powershell
docker compose -f compose.postgres.yml -f compose.catalog.yml -f compose.orders.yml -f compose.notify.yml -f compose.kafka.yml stop
```

No usar `down -v` ni restablecer Docker a valores de fábrica para solucionar errores normales: los volúmenes contienen las bases, colas y eventos.

## Pruebas

```powershell
Set-Location -LiteralPath 'D:\Cloud_Native_EVA\ms-pedidos360-orders'
.\mvnw.cmd clean verify -Ppostgres-it
Set-Location -LiteralPath 'D:\Cloud_Native_EVA'
node scripts/test-orders-flow.mjs --kafka
node scripts/test-orders-flow.mjs --notify --kafka
node scripts/test-notify-compose.mjs --kafka
```

Antes de los scripts deben existir los JAR de BFF, Catalog, Orders y, para `--notify`, Notify. Compilarlos con `clean verify` si faltan. Se requieren Java 21 o superior, Node 22+ y Docker con contenedores Linux.

Las pruebas usan credenciales aleatorias y recursos temporales separados. Comprueban eventos reales leídos desde Kafka, versiones, actores, contenido, recuperación después de una caída y un reinicio, e independencia de RabbitMQ. La prueba Compose también verifica los puertos locales, los cuatro servicios Java sin root y la creación del tópico. Al terminar se retiran únicamente los recursos temporales.

Resultados verificados en este bloque:

- Orders: 100 pruebas rápidas y 66 con PostgreSQL, todas aprobadas.
- Flujo con ambos brokers: 68 comprobaciones HTTP y de pedidos, 17 de RabbitMQ/SMTP y 21 de Kafka.
- Docker Compose: nueve contenedores activos, inicializador terminado con código 0, tópico con tres particiones y cuatro servicios Java `UP` con usuario `10001`.

## Próximo paso

Crear Audit como consumidor con grupo propio, deduplicación persistente y consulta protegida para ADMIN/AUDITOR. Report tendrá otro grupo independiente, para recibir todos los eventos y no competir con Audit por los mensajes. El contrato HTTP de ambos servicios sigue siendo el acordado.

## Referencias

Se utilizó la [imagen oficial de Apache Kafka 4.3.1](https://kafka.apache.org/community/downloads/), la [configuración del productor](https://kafka.apache.org/43/configuration/producer-configs/) y la [integración de Spring Boot con Kafka](https://docs.spring.io/spring-boot/reference/messaging/kafka.html).
