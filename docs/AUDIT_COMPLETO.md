# Audit: historial básico de pedidos

## Qué está implementado

Audit consume el tópico Kafka `orders.events` con un grupo propio, guarda los eventos en PostgreSQL y ofrece consultas de solo lectura. El BFF ya utiliza estas consultas.

No recibe eventos mediante POST ni permite editarlos o borrarlos desde la API. Esta auditoría registra cambios de pedidos; no registra todos los accesos HTTP, operaciones de catálogo ni intentos de login.

## Flujo y seguridad

Orders guarda cada cambio y su evento pendiente en una misma transacción. Su publicador lo envía a Kafka. Audit valida el mensaje, lo guarda y recién después permite confirmar su posición en Kafka.

El consumo es al menos una vez. Los índices únicos de eventId y (orderId, aggregateVersion) evitan repetir filas, incluso al reiniciar o recibir dos copias simultáneas. Una copia idéntica se acepta sin modificar la primera fila. Un identificador o versión con contenido diferente se registra como conflicto.

Los eventos atrasados se conservan: no se exige recibir primero OrderCreated ni versiones consecutivas. Esto también permite consumir cambios de pedidos anteriores a la incorporación de Kafka. No se inventan eventos históricos.

Los endpoints requieren un JWT válido para el emisor y audiencia configurados, scope pedidos360.access y rol ADMIN o AUDITOR. Se validan firma, expiración, emisor y audiencia tanto en BFF como en Audit. No hay tokens de acceso dentro de los eventos.

La base pedidos360_audit tiene su propio usuario sin privilegios de administración. Flyway crea las tablas. El usuario de ejecución es dueño de esta base para poder aplicar migraciones: no es una cuenta de solo lectura ni un registro inalterable ante un administrador de base de datos. Separar migraciones de ejecución y habilitar respaldos queda para endurecer el despliegue.

## Consultas

- GET /api/v1/audit
- GET /api/v1/audit/orders/{orderId}

Parámetros: afterId, entero no negativo, por defecto 0; size, de 1 a 100, por defecto 50. El id del pedido debe ser positivo.

Respuesta de ejemplo:

```json
{
  "items": [
    {
      "id": 1,
      "event": {
        "schemaVersion": 1,
        "eventId": "ab7d7193-5b85-4128-a28a-34c222d84014",
        "eventType": "OrderCreated",
        "orderId": 10,
        "aggregateVersion": 1,
        "occurredAt": "2026-09-12T12:00:00Z",
        "traceId": "pedido-10",
        "actorId": "alice",
        "customerId": "alice",
        "previousStatus": null,
        "status": "CREADO",
        "createdAt": "2026-09-12T12:00:00Z",
        "total": 100.50
      },
      "recordedAt": "2026-09-12T12:00:01Z"
    }
  ],
  "nextAfterId": null
}
```

Cada event conserva el contrato v1 de Orders. id es un identificador local de Audit, distinto de eventId y de orderId. Se ordena por id ascendente, según el registro en Audit. Para reconstruir la secuencia de un pedido se utiliza aggregateVersion; recordedAt no representa cuándo ocurrió el cambio.

Si nextAfterId tiene un valor, usarlo como afterId para pedir la siguiente página con el mismo filtro. null indica que no había más filas al consultar. No es un snapshot de exportación: pueden llegar eventos mientras se pagina. Para refrescar una vista se puede volver a consultar desde 0.

Un pedido sin historial devuelve 200 con items vacío, incluso si no existe en Orders. Audit no consulta otras bases para verificarlo. La información es eventual: un cambio recién realizado puede tardar en aparecer.

## Fallas y mensajes inválidos

Si PostgreSQL falla, el consumidor reintenta cada dos segundos sin descartar el mensaje ni confirmar ese registro. El listener procesa un registro por vez y confirma después de que la transacción de base termina. Los siguientes mensajes pueden esperar mientras se recupera la base.

La conexión JDBC limita a tres segundos la conexión inicial y a diez segundos la espera de lectura. Así, una base que acepta conexiones pero deja de responder también puede provocar reintento; la espera no queda indefinida.

El parser exige los 13 campos, schemaVersion 1, estados/transiciones válidos, clave Kafka igual a orderId, tipos correctos y tamaño máximo de 32 KiB. Rechaza propiedades extra, campos ausentes, enteros fraccionarios, propiedades duplicadas y contenido JSON adicional.

Un mensaje inválido o conflictivo se registra en audit_rejections con motivo, tópico, partición, offset, fecha y SHA-256 del contenido. Después se confirma y continúa el consumo. No se guarda el cuerpo inválido, posibles tokens ni datos completos del mensaje en esta tabla. Un fallo al guardar el rechazo también provoca reintento.

Esta tabla es un registro técnico, no una cola de reenvío ni un endpoint público. Para investigar, consultar reason, source_topic, source_partition y source_offset usando acceso administrativo autorizado a la base; el contenido original solo estará en Kafka mientras dure la retención. Las correcciones deben revisarse antes de republicar: nunca reemplazan una entrada válida existente.

El mismo eventId con total expresado como 100.5 o 100.50 se trata como duplicado equivalente. No se aplican cambios en la auditoría a partir de un mensaje conflictivo.

## Ejecutar localmente

Requiere Docker Desktop funcionando. Configurar en el .env privado las variables de .env.example, incluyendo una clave diferente para AUDIT_DB_PASSWORD. No subir ese .env ni JWT a GitHub. La cuenta real de Entra y sus permisos siguen siendo necesarios para usar las rutas protegidas fuera de las pruebas automáticas.

Desde la raíz:

```powershell
docker compose -f compose.postgres.yml -f compose.catalog.yml -f compose.orders.yml -f compose.notify.yml -f compose.kafka.yml -f compose.audit.yml up -d --build
```

Este comando incorpora Audit a la solución local. No usar down --volumes para detener el entorno de desarrollo: eliminaría sus datos. No hace falta resetear Docker ni recrear las bases existentes.

Puertos predeterminados: BFF 8080, Audit 8085 y PostgreSQL de Audit 5434, publicados solo en loopback. Dentro de Docker, Audit usa postgres-audit:5432 y kafka:19092. El BFF usa http://audit:8085. Los contenedores Java ejecutan como usuario 10001.

Con Java local, configurar AUDIT_DB_URL, AUDIT_DB_USERNAME, AUDIT_DB_PASSWORD, JWT_ISSUER_URI, JWT_AUDIENCE y KAFKA_BOOTSTRAP_SERVERS en el proceso; Spring Boot no carga .env automáticamente. Ejecutar .\mvnw.cmd spring-boot:run desde ms-pedidos360-audit.

AUDIT_KAFKA_GROUP vale pedidos360-audit-v1. No cambiarlo en cada reinicio ni compartirlo con Report. Un grupo nuevo comienza desde el primer evento aún disponible; no recupera mensajes ya eliminados por Kafka.

AUDIT_EVENTS_ENABLED=false sirve para pruebas aisladas; la readiness y la salud global indican OUT_OF_SERVICE para no presentar un consumidor desactivado como listo. La liveness sigue disponible. Con consumo habilitado, readiness comprueba la base y que el listener esté activo con particiones asignadas. No mide atraso, ni garantiza detectar inmediatamente una desconexión del broker.

Swagger queda desactivado por defecto; habilitar API_DOCS_ENABLED solo para desarrollo. Los errores propios de autenticación son 401/403; consultas inválidas, 400; fallo de base en Audit, 503. El BFF traduce fallos del servicio a 502 sin exponer detalles internos.

## Pruebas reproducibles

Desde ms-pedidos360-audit:

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd -Ppostgres-it verify
```

El primer comando prueba el parser sin Docker. El segundo también verifica persistencia real, carreras de duplicados, conflictos, paginación, JWT y permisos con PostgreSQL temporal.

Compilar los cinco proyectos Java antes de la prueba de flujo: BFF, Catalog, Orders, Notify y Audit. Luego, desde la raíz:

```powershell
node scripts/test-orders-flow.mjs --notify --audit
node scripts/test-notify-compose.mjs --audit
```

--audit incluye Kafka. El flujo usa JWT firmados por un emisor local exclusivo de pruebas; no reemplaza la prueba con Entra real. Verifica eventos reales de Orders, consultas por el BFF, reinicio de Audit, duplicados, mensajes inválidos y recuperación después de pausar su base durante 25 segundos.

Las pruebas crean nombres, contraseñas, contenedores y volúmenes temporales propios y los retiran al finalizar. No utilizan los datos de desarrollo. El segundo comando verifica además las imágenes y la configuración Compose completa.

Importar postman/Pedidos360-Audit.postman_collection.json para consultas manuales. Definir baseUrl, accessToken, orderId y los parámetros de página solo en Postman. El repositorio no incluye tokens.

Resultados verificados el 12 de septiembre de 2026:

- Audit: 36 pruebas del parser y 27 con PostgreSQL real, todas aprobadas.
- BFF: 150 pruebas aprobadas.
- Flujo completo: 90 comprobaciones HTTP y de pedidos, 17 RabbitMQ/SMTP, 21 Kafka y 14 de persistencia/recuperación de Audit; 142 en total.
- Compose: once contenedores activos y un inicializador terminado correctamente. Los cinco servicios Java quedaron UP y ejecutando como usuario 10001.

## Lo que sigue pendiente

Report, integración con frontend, credenciales y roles reales de Entra, y despliegue/pruebas en AWS. Kafka local usa PLAINTEXT: antes de exponerlo en cloud se necesitan red privada, TLS/SASL y permisos de tópicos. También faltan respaldos, política de conservación de Audit, alertas de atraso/rechazos y una estrategia de recuperación fuera de los siete días de retención de Kafka.

No se borran filas de Audit automáticamente. Esto conserva el historial para la evaluación, pero requiere definir una política de retención si el sistema sigue creciendo.

Referencias: [confirmación de registros en Spring Kafka](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/message-listener-container.html) y [manejo de errores y reintentos](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html).
