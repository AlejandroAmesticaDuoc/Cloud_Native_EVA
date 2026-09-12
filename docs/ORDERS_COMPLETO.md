# Orders: desarrollo e integración local

Este bloque incorpora el microservicio de pedidos y su comunicación real con Catalog. El código se desarrolla en `feat/services-aws-integration`; las correcciones del BFF se guardan primero en `feat/bff-jwt-security` y se incorporan a servicios.

## Contrato y permisos

El frontend utiliza el BFF en el puerto 8080. Orders responde en 8081 y vuelve a validar el JWT. Todas sus rutas requieren scope `pedidos360.access` además de los roles de la tabla.

| Método y ruta | Roles | Respuesta |
|---|---|---|
| `GET /api/v1/orders` | ADMIN, OPERADOR, CLIENTE | `200`, arreglo de pedidos |
| `GET /api/v1/orders/{id}` | ADMIN, OPERADOR, CLIENTE | `200`, pedido |
| `POST /api/v1/orders` | CLIENTE, OPERADOR | `201`, pedido creado |
| `PATCH /api/v1/orders/{id}/status` | ADMIN, OPERADOR | `200`, pedido actualizado |
| `POST /api/v1/orders/{id}/cancel` | ADMIN, OPERADOR, CLIENTE | `204`, sin cuerpo |

Un cliente solo puede listar, consultar o cancelar sus propios pedidos. Orders aplica esta regla incluso si alguien intenta saltarse el BFF. Un administrador no crea pedidos en esta versión, tal como establece el contrato inicial.

Al crear, el frontend envía solamente:

```json
{"items":[{"productId":10,"quantity":2}]}
```

El BFF agrega `customerId` usando `oid` o, si falta, `sub`. Orders comprueba que coincida con la identidad autenticada. Un operador crea para su propia identidad; no puede indicar otro cliente.

Los precios se consultan en Catalog con el token del usuario. Orders guarda ese precio como una copia histórica y calcula el total con `BigDecimal`; cambiar el precio del catálogo después no modifica pedidos anteriores. No se aceptan precios ni totales enviados por el cliente.

Se permiten entre 1 y 50 productos distintos. IDs y cantidades son enteros positivos, sin truncar decimales. Un producto inexistente o desactivado impide crear el pedido. Crear no reserva stock: la disponibilidad definitiva se comprueba al aceptar.

## Estados y stock

```text
CREADO → ACEPTADO → EN_PREPARACION → DESPACHADO → ENTREGADO
```

El recorrido normal no permite saltos ni retrocesos. Cancelar desde `EN_PREPARACION`, `DESPACHADO` o `ENTREGADO` devuelve `409`. Repetir el estado actual o cancelar nuevamente un pedido cancelado no cambia datos ni repite stock.

- Aceptar descuenta las cantidades del pedido mediante Catalog.
- Si no alcanza el stock, se conserva `CREADO` y se devuelve `409`.
- Cancelar desde `CREADO` no mueve stock.
- Cancelar desde `ACEPTADO` devuelve las cantidades registradas.
- Enviar `CANCELADO` mediante PATCH aplica las mismas reglas que el endpoint de cancelación.

## Respuestas perdidas y reintentos

No hay una transacción de base de datos compartida entre Orders y Catalog. Antes de llamar al stock, Orders confirma una intención en su propia base (`pending_status`). Después bloquea el pedido, llama a Catalog y confirma el estado si recibe la respuesta esperada.

Si ocurre un timeout, se pierde una respuesta o el proceso se reinicia, la intención queda guardada. No se permite pasar a otra acción mientras esté pendiente. Reintentar exactamente la aceptación o cancelación original vuelve a usar el mismo `orderId`; Catalog impide repetir el movimiento.

Para distinguir falta de stock de un conflicto incierto, Catalog devuelve `X-Stock-Result: rejected` cuando confirma stock insuficiente. Un `409` genérico de Catalog se trata como resultado no confirmado: Orders mantiene la intención. Los bloqueos y la segunda revisión del movimiento en Catalog evitan confundir un descuento concurrente ya realizado con falta de stock.

La recuperación requiere reintentar la misma acción cuando la dependencia vuelva a funcionar. No hay un worker de recuperación automática. Si persiste el error, revisar los logs y el movimiento antes de intervenir; no borrar ni modificar `pending_status` manualmente como solución rápida.

Las peticiones de creación no tienen idempotencia todavía: ante un resultado dudoso de POST, revisar el listado antes de repetirlo para evitar crear dos pedidos.

## Identidad técnica de Orders en Entra

El BFF conserva el token del usuario. Orders lo utiliza para consultar productos, pero obtiene un token propio para descontar o devolver stock. Esta comunicación usa OAuth2 client credentials, sin elevar los permisos del cliente. [Flujo oficial de Microsoft](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-client-creds-grant-flow).

Configuración pendiente en el tenant real:

1. En el registro de la API que ya usa el BFF, crear el app role `CATALOG_STOCK_WRITE`, habilitado y con tipo de miembro **Applications**.
2. Crear un registro adicional para la identidad técnica de Orders. No necesita redirect de frontend.
3. En ese registro, agregar el permiso de aplicación `CATALOG_STOCK_WRITE` de nuestra API y conceder el consentimiento administrativo correspondiente. No asignarlo a Angular ni a usuarios. [Roles y permisos de aplicación](https://learn.microsoft.com/en-us/entra/identity-platform/howto-add-app-roles-in-apps).
4. Verificar que la API espere Access Tokens v2: `api.requestedAccessTokenVersion` debe ser `2`. El endpoint v2 por sí solo no determina la versión del Access Token. [Configuración de la API en Microsoft Graph](https://learn.microsoft.com/en-us/graph/api/resources/apiapplication?view=graph-rest-1.0).
5. Configurar la credencial de Orders fuera del repositorio. Para este desarrollo se admite client secret; la gestión y rotación de credenciales de producción se revisará con infraestructura.

Catalog comprueba firma, issuer, audience, expiración, rol `CATALOG_STOCK_WRITE`, ausencia de scope delegado `scp` y `azp` igual al client ID configurado para Orders. Ese token no autoriza el CRUD público del catálogo ni rutas del BFF.

La configuración local de client credentials utiliza los componentes de Spring Security para obtener y reutilizar el token hasta su renovación. [Clientes OAuth2 autorizados](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorized-clients.html).

No se ha creado ni modificado ningún registro real de Entra durante este desarrollo. Las pruebas automatizadas usan un emisor temporal y credenciales aleatorias o exclusivas de test.

## Variables

| Variable | Uso |
|---|---|
| `JWT_ISSUER_URI` | Mismo issuer v2 del tenant en BFF, Orders y Catalog |
| `JWT_AUDIENCE` | Mismo client ID de la API en los tres servicios |
| `ORDERS_SERVICE_CLIENT_ID` | Client ID del registro técnico de Orders; también se configura en Catalog |
| `ORDERS_SERVICE_CLIENT_SECRET` | Credencial del registro técnico, solo en Orders |
| `ORDERS_TOKEN_URI` | `https://login.microsoftonline.com/<tenant-id>/oauth2/v2.0/token` |
| `ORDERS_CATALOG_SCOPE` | Application ID URI de la API seguido de `/.default`, normalmente `api://<api-client-id>/.default` |
| `ORDERS_DB_URL` | En Windows: `jdbc:postgresql://localhost:5433/pedidos360_orders` |
| `ORDERS_DB_USERNAME` | `pedidos360_orders` |
| `ORDERS_DB_PASSWORD` | Contraseña exclusiva del usuario SQL de Orders |
| `ORDERS_POSTGRES_PORT` | Puerto local de su base, `5433` por defecto |
| `CATALOG_SERVICE_URL` | Para Orders local: `http://localhost:8082`; dentro de Docker: `http://catalog:8082` |

La base de Orders usa otro contenedor y volumen. Esto mantiene intacto el volumen de Catalog y evita depender de que un nuevo script se ejecute en una base que ya fue inicializada. El usuario de aplicación no es superusuario.

## Arranque con Docker Compose

Definir primero las variables de la [guía PostgreSQL](POSTGRESQL_LOCAL.md), manteniendo las contraseñas originales de Catalog. En la misma terminal agregar una contraseña distinta para Orders:

```powershell
$env:ORDERS_DB_PASSWORD = [System.Net.NetworkCredential]::new(
    '', (Read-Host 'Contraseña de la base de Orders' -AsSecureString)
).Password
```

Cuando esté lista la identidad técnica, definir sus valores reales y solicitar el secreto sin escribirlo en el historial:

```powershell
$env:ORDERS_SERVICE_CLIENT_ID = '<client-id-tecnico-orders>'
$env:ORDERS_TOKEN_URI = 'https://login.microsoftonline.com/<tenant-id>/oauth2/v2.0/token'
$env:ORDERS_CATALOG_SCOPE = 'api://<api-client-id>/.default'
$env:ORDERS_SERVICE_CLIENT_SECRET = [System.Net.NetworkCredential]::new(
    '', (Read-Host 'Client secret de Orders' -AsSecureString)
).Password
```

Los marcadores deben reemplazarse; no son credenciales funcionales. Sin la identidad técnica, los servicios pueden iniciar, pero no se puede completar una aceptación ni una devolución de stock. No desactivar JWT para solucionarlo.

Desde la raíz, sin ejecutar simultáneamente los mismos servicios con Maven:

```powershell
Set-Location -LiteralPath 'D:\Cloud_Native_EVA'
docker compose -f compose.postgres.yml -f compose.catalog.yml -f compose.orders.yml up -d --build
docker compose -f compose.postgres.yml -f compose.catalog.yml -f compose.orders.yml ps
```

Esperar las respuestas `UP` de:

```powershell
Invoke-RestMethod http://localhost:8082/actuator/health/readiness
Invoke-RestMethod http://localhost:8081/actuator/health/readiness
Invoke-RestMethod http://localhost:8080/actuator/health
```

Compose espera la salud de las bases, pero el orden de inicio de aplicaciones no garantiza que sus endpoints estén listos inmediatamente. Si un puerto está ocupado, elegir otro con las variables correspondientes sin detener servicios ajenos.

Todos los puertos publicados están limitados a `127.0.0.1`. Detener sin borrar datos:

```powershell
docker compose -f compose.postgres.yml -f compose.catalog.yml -f compose.orders.yml stop
```

No usar `down -v` ni eliminar los volúmenes de desarrollo. Cambiar una variable de contraseña después de crear una base no cambia su contraseña interna.

## Pruebas reproducibles

Resultado del bloque: BFF 124 pruebas; Catalog 69 rápidas y 47 con PostgreSQL; Orders 84 rápidas y 53 con PostgreSQL. Todas pasaron. La integración HTTP agregó 64 comprobaciones; el Compose de cinco contenedores arrancó con los tres servicios en Java 21 y usuario sin root.

Requiere Java, Node 22+ y Docker Desktop con contenedores Linux:

```powershell
Set-Location -LiteralPath 'D:\Cloud_Native_EVA\ms-pedidos360-bff'
.\mvnw.cmd clean verify
Set-Location -LiteralPath 'D:\Cloud_Native_EVA\ms-pedidos360-catalog'
.\mvnw.cmd clean verify -Ppostgres-it
Set-Location -LiteralPath 'D:\Cloud_Native_EVA\ms-pedidos360-orders'
.\mvnw.cmd clean verify -Ppostgres-it
Set-Location -LiteralPath 'D:\Cloud_Native_EVA'
node scripts/test-orders-flow.mjs
```

La integración arranca JAR reales, dos bases separadas dentro de un PostgreSQL temporal y un emisor local de tokens. Comprueba propiedad, precios históricos, estados, stock insuficiente, cancelación por cliente, concurrencia y recuperación tras una respuesta perdida y un reinicio. Al terminar retira sus procesos y su contenedor; no mantiene los servicios disponibles para Postman.

Para pruebas manuales, importar [Pedidos360 Orders](../postman/Pedidos360-Orders.postman_collection.json). Usar Access Tokens reales de administrador, operador y cliente, guardados solo en valores locales o entornos privados. No exportarlos ni subirlos a GitHub. La colección crea datos de prueba: no ejecutarla en producción.

## Pendiente de la solución completa

Este bloque no incluye RabbitMQ, Kafka, Notify, Audit ni Report. Tampoco demuestra el login Angular/MSAL con usuarios reales ni el despliegue AWS. Esos componentes se trabajan después del flujo principal de pedidos y stock.
