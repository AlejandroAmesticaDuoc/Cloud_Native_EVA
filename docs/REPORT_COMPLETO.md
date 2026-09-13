# Report: KPIs básicos de Pedidos360

## Alcance

Report consume orders.events con su propio grupo Kafka y mantiene una copia resumida del último estado de cada pedido en PostgreSQL. El BFF ofrece las consultas solo a ADMIN. No consulta Orders ni comparte tablas o usuarios con Audit.

La revisión del caso semestral confirmó tres indicadores mínimos: ventas por hora, estados activos y lead time. El caso define lead time desde la creación del pedido hasta su entrega. Las pantallas de productos más vendidos aparecen como ejemplos; no se implementan en este bloque porque el evento actual no incluye el detalle de productos.

Para este alcance, “ventas” corresponde al monto de pedidos ENTREGADOS, agrupados por la hora de entrega. Es una decisión del proyecto: el caso no fija el momento de reconocimiento de la venta. No representa pagos, facturas ni ingresos contables confirmados. No se asume conversión de monedas.

## Datos y consumo

- report_events: registro de eventos aceptados, con eventId y (orderId, aggregateVersion) únicos.
- report_orders: un registro por pedido, con la versión más reciente, estado, creación, fecha del cambio y total.
- report_rejections: motivo, tópico, partición, offset, fecha y SHA-256 del mensaje inválido o conflictivo.

El registro del evento y la actualización del pedido se guardan en la misma transacción. Kafka confirma el registro después de que termina esa transacción. Si la base falla, se reintenta sin descartar el evento. Las esperas JDBC de conexión y lectura están limitadas a tres y diez segundos respectivamente.

Un duplicado idéntico no vuelve a actualizar los KPIs. Un eventId repetido con otro contenido o una versión ocupada por otro evento se registra como conflicto. Un evento válido de una versión anterior se conserva, pero nunca reemplaza la versión más nueva.

Report puede comenzar con un evento distinto de OrderCreated para un pedido antiguo. Cada evento trae createdAt y total, por lo que no se necesita fabricar un evento de creación. No se exige que lleguen todas las versiones intermedias.

Se aplica la validación del contrato v1 y se exige occurredAt >= createdAt para evitar duraciones negativas. Los mensajes inválidos quedan identificados en report_rejections y no afectan las estadísticas. No se guarda el cuerpo inválido completo, posibles tokens ni stack traces en esa tabla.

Un rechazo solo se confirma cuando pudo guardarse en PostgreSQL. El contenido original queda disponible en Kafka únicamente durante su retención. Esta tabla no es una cola de reenvío ni tiene un endpoint público.

El cálculo utiliza el último snapshot válido emitido por Orders y confía en sus reglas de negocio. Report no reimplementa toda la máquina de estados ni los controles de stock.

## GET /api/v1/reports/summary

Parámetro opcional: hours, entero de 1 a 168, por defecto 24.

Ejemplo con hours=2:

```json
{
  "totalOrders": 3,
  "activeOrders": 1,
  "deliveredOrders": 1,
  "cancelledOrders": 1,
  "ordersByStatus": {
    "CREADO": 1,
    "ACEPTADO": 0,
    "EN_PREPARACION": 0,
    "DESPACHADO": 0,
    "ENTREGADO": 1,
    "CANCELADO": 1
  },
  "deliveredAmount": 1250.50,
  "hourlyFrom": "2026-09-12T13:00:00Z",
  "hourlyTo": "2026-09-12T15:00:00Z",
  "salesByHour": [
    {
      "hour": "2026-09-12T13:00:00Z",
      "deliveredOrders": 1,
      "deliveredAmount": 1250.50
    },
    {
      "hour": "2026-09-12T14:00:00Z",
      "deliveredOrders": 0,
      "deliveredAmount": 0.00
    }
  ]
}
```

totalOrders cuenta pedidos distintos observados, no eventos. activeOrders incluye CREADO, ACEPTADO, EN_PREPARACION y DESPACHADO. Los seis estados siempre aparecen en ordersByStatus, aunque tengan cantidad cero.

Los contadores principales y deliveredAmount consideran toda la proyección disponible. hours solo limita salesByHour. Por eso, el monto general puede ser mayor que la suma de la ventana horaria.

La ventana incluye la hora UTC en curso y termina al inicio de la siguiente hora. Su extremo inicial es inclusivo y el final es exclusivo. Si se consulta a las 14:30 UTC con hours=2, cubre de 13:00 a 15:00 UTC. La última hora puede estar incompleta.

Cada hora aparece una vez, en orden cronológico, incluso si no hubo entregas. La fecha utilizada es occurredAt del evento que dejó el pedido en ENTREGADO, no el momento en que Report recibió el mensaje. El frontend puede formatear las fechas para la zona local sin cambiar los agrupamientos.

Las consultas del resumen usan una misma instantánea de base de datos para que los contadores y la serie horaria no mezclen dos estados del consumidor dentro de una respuesta.

## GET /api/v1/reports/lead-time

No recibe parámetros. Considera todos los pedidos cuyo último estado conocido es ENTREGADO.

```json
{
  "deliveredOrders": 2,
  "averageSeconds": 3600.000,
  "minimumSeconds": 1800.000,
  "maximumSeconds": 5400.000
}
```

Para cada pedido se calcula entrega menos creación. Después se obtienen promedio, mínimo y máximo, en segundos con tres decimales. Se excluyen pedidos activos y cancelados.

Si todavía no hay entregas:

```json
{
  "deliveredOrders": 0,
  "averageSeconds": null,
  "minimumSeconds": null,
  "maximumSeconds": null
}
```

null significa “sin datos”, no una entrega instantánea. Una entrega con duración real cero sí puede dar 0.000.

## Seguridad y consistencia

Ambos endpoints requieren JWT válido, scope pedidos360.access y rol ADMIN. CLIENTE, OPERADOR y AUDITOR no tienen acceso. No hay endpoints de escritura. Las verificaciones se realizan tanto en BFF como en Report.

Sin token o con token inválido se obtiene 401; sin rol/scope suficiente, 403; hours inválido, 400. Un fallo de la base en Report devuelve 503 controlado. Si Report no responde o entrega un error, el BFF devuelve 502 sin exponer el cuerpo interno.

Los reportes son eventualmente consistentes: una operación puede tardar en aparecer. No son una consulta en vivo a la base transaccional de Orders. Tampoco acreditan que se haya procesado todo Kafka; readiness no mide atraso.

El grupo predeterminado es pedidos360-report-v1. Audit usa pedidos360-audit-v1. No deben compartir grupo: ambos necesitan recibir todos los eventos.

## Ejecución local

Configurar las variables del .env.example en el .env privado, incluyendo REPORT_DB_PASSWORD con una contraseña diferente. No subir el .env ni tokens a GitHub.

Desde la raíz:

```powershell
docker compose -f compose.postgres.yml -f compose.catalog.yml -f compose.orders.yml -f compose.notify.yml -f compose.kafka.yml -f compose.audit.yml -f compose.report.yml up -d --build
```

Son siete archivos combinados en un solo proyecto Compose. Agregar Report no requiere eliminar las bases existentes ni restablecer Docker. Para detener el entorno, no utilizar down --volumes: esa opción elimina los datos.

Puertos predeterminados: BFF 8080, Report 8084 y PostgreSQL de Report 5435, publicados únicamente en 127.0.0.1. Dentro de Docker, Report utiliza postgres-report:5432 y kafka:19092; el BFF usa http://report:8084.

El usuario pedidos360_report no tiene privilegios de administración, creación de bases ni creación de roles. Es dueño de su base para ejecutar Flyway. Separar la cuenta de migraciones de la de ejecución queda como mejora de despliegue. El contenedor Java ejecuta con usuario 10001.

Para ejecutar Report con Java en Windows, configurar REPORT_DB_URL, REPORT_DB_USERNAME, REPORT_DB_PASSWORD, JWT_ISSUER_URI, JWT_AUDIENCE y KAFKA_BOOTSTRAP_SERVERS en el proceso. Spring Boot no carga .env automáticamente. Luego ejecutar .\mvnw.cmd spring-boot:run desde ms-pedidos360-report.

REPORT_EVENTS_ENABLED=false permite pruebas aisladas pero deja la salud global/readiness en OUT_OF_SERVICE. Liveness sigue disponible. Con consumo habilitado, readiness comprueba la base y que el listener esté activo con particiones asignadas. Una desconexión del broker puede tardar en reflejarse; no es una alerta de lag.

Swagger está desactivado por defecto; habilitar API_DOCS_ENABLED=true solo para desarrollo.

## Pruebas

Desde ms-pedidos360-report:

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd -Ppostgres-it verify
```

El primer comando prueba la validación del evento sin Docker. El segundo verifica además migraciones, permisos de base, JWT, conteos, UTC, duraciones, duplicados, concurrencia y reversión de transacciones con PostgreSQL real.

Para las pruebas completas, compilar primero BFF, Catalog, Orders, Notify, Audit y Report. No recompilar sus JAR mientras la prueba los está ejecutando. Desde la raíz:

```powershell
node scripts/test-orders-flow.mjs --notify --report
node scripts/test-notify-compose.mjs --report
```

--report incluye Kafka y Audit. El flujo usa eventos reales de Orders y JWT firmados por un emisor local de prueba. Comprueba que Report reciba los eventos que también procesa Audit, calcula los KPIs de forma independiente para compararlos, prueba eventos atrasados, duplicados, reinicio y 25 segundos con PostgreSQL sin responder.

Los scripts crean y eliminan únicamente sus propios contenedores y volúmenes temporales. No utilizan las bases de desarrollo. Las contraseñas se generan para cada ejecución y no se guardan en Git. Estas pruebas no sustituyen el login con usuarios reales de Entra.

La colección postman/Pedidos360-Report.postman_collection.json incluye las consultas del BFF. Completar baseUrl y accessToken solo en Postman, utilizando un usuario ADMIN con el scope requerido.

Resultados verificados el 12 de septiembre de 2026:

- Report: 36 pruebas de validación de eventos y 30 con PostgreSQL real; 66 aprobadas, sin fallos ni pruebas omitidas.
- BFF: 174 pruebas aprobadas en feat/bff-jwt-security. El mismo código está integrado en feat/services-aws-integration.
- Flujo completo: 126 comprobaciones HTTP y de pedidos, 17 RabbitMQ/SMTP, 21 Kafka, 14 de Audit y 33 de KPIs/recuperación de Report; 211 en total.
- Compose: trece contenedores activos y un inicializador terminado correctamente. Los seis servicios Java quedaron UP y ejecutando como usuario 10001.

La primera ejecución de Compose se detuvo al iniciar RabbitMQ mientras también corría el flujo completo. La repetición aislada pasó; no se confirmó la causa del primer fallo. Conviene ejecutar ambos scripts de forma secuencial. Se agregó captura de logs del broker si vuelve a ocurrir.

## Límites y siguiente etapa

Los datos reflejan los eventos que Report alcanzó a consumir. Kafka local retiene siete días; iniciar un consumidor nuevo después de ese plazo no reconstruye por sí solo todo el historial anterior. No existe backfill automático desde Orders o Audit.

Los eventos aceptados y la proyección no se limpian automáticamente. Se requieren respaldos, política de retención, alertas de atraso y procedimiento de reconstrucción antes de un uso permanente. Los KPIs recorren la proyección local y son suficientes para el MVP; no se presenta este diseño como analítica de gran volumen.

Queda integrar las vistas Angular, probar Entra real y desplegar/verificar en AWS. El caso original menciona Oracle y Kafka con ZooKeeper; el repositorio utiliza PostgreSQL por decisión del equipo y Kafka KRaft. Estas diferencias deben comunicarse y validarse con el docente, sin asumir que ya aprobó excepciones.

La pauta EP1 se concentra en MSAL/Angular y validación JWT del BFF. Completar estos KPIs no reemplaza esas verificaciones ni demuestra por sí solo el cumplimiento de la evaluación.

Referencias técnicas: [UPSERT en PostgreSQL 17](https://www.postgresql.org/docs/17/sql-insert.html), [funciones de agregación](https://www.postgresql.org/docs/17/functions-aggregate.html) y [reintentos del consumidor Spring Kafka](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html).
