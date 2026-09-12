# MS Pedidos360 BFF

Backend for Frontend del proyecto Pedidos360, desarrollado para la Evaluación Parcial 1 de Desarrollo Cloud Native I.

Este componente recibe las solicitudes del frontend, valida la seguridad y se comunica con los microservicios internos.

## Estado actual

Se encuentra implementado:

- Validación JWT: firma, issuer, audience y vigencia.
- Autorización por scope y roles.
- Configuración CORS.
- Consulta del usuario autenticado.
- Endpoints de pedidos y catálogo.
- Clientes HTTP para Orders y Catalog.
- Propagación del Access Token y X-Trace-Id hacia los servicios.
- Manejo de errores y tiempos de espera.
- Documentación Swagger/OpenAPI.

Al cierre de este bloque se verificaron 124 pruebas exitosas.

El BFF conserva el `403` cuando Orders rechaza el acceso por propiedad del pedido. Los IDs, cantidades y valores de stock enviados como decimales se rechazan con `400`; no se truncan a enteros.

El JWT debe incluir expiración, además de superar la validación de firma, emisor, audiencia y vigencia. Un identificador no numérico devuelve `400` y un contenido incompatible devuelve `415`.

`X-Trace-Id` se valida con el patrón `[A-Za-z0-9._-]{1,100}`. Si falta o no cumple el formato, se genera uno nuevo. El mismo identificador se utiliza en las llamadas internas, las respuestas y los errores.

Esto no significa que la integración completa esté terminada. Todavía debemos probar con el tenant real de Entra ID, los microservicios del equipo, el frontend y AWS API Gateway.

Las rutas de auditoría y reportería tienen reglas de autorización, pero sus controladores y clientes HTTP reales aún no están implementados.

## Responsabilidad del BFF

El flujo local será:

```text
Frontend Angular -> BFF -> Orders / Catalog -> Oracle Cloud
```

El flujo previsto en AWS será:

```text
Frontend Angular -> AWS API Gateway -> BFF -> Microservicios
```

El BFF vuelve a validar el token aunque API Gateway ya lo haya validado.

Este componente no se conecta directamente a Oracle Cloud. La persistencia, las transiciones de estado, el descuento de stock y la mensajería corresponden a los microservicios.

## Tecnologías

- Java 21 como objetivo de compilación.
- Spring Boot 4.1.1.
- Spring Security y OAuth2 Resource Server.
- Maven Wrapper.
- RestClient.
- Springdoc OpenAPI 3.1.0.
- JUnit, MockMvc y herramientas de prueba de Spring.

## Preparación

Abrir una terminal en la carpeta `ms-pedidos360-bff`.

Comprobar las herramientas:

```powershell
java -version
javac -version
.\mvnw.cmd -v
```

Maven Wrapper permite ejecutar Maven sin instalarlo globalmente. La primera ejecución puede necesitar conexión a Internet para descargar dependencias.

## Variables de entorno

| Variable | Uso / valor de referencia |
|---|---|
| `BFF_PORT` | Puerto del BFF. Por defecto: `8080`. |
| `JWT_ISSUER_URI` | Issuer del tenant: `https://login.microsoftonline.com/<tenant-id>/v2.0`. |
| `JWT_AUDIENCE` | Audience acordada para la API protegida. No corresponde al Client ID del frontend. |
| `ORDERS_SERVICE_URL` | URL base de Orders. Por defecto: `http://localhost:8081`. |
| `CATALOG_SERVICE_URL` | URL base de Catalog. Por defecto: `http://localhost:8082`. |
| `ALLOWED_ORIGINS` | Orígenes permitidos, separados por coma. Por defecto: `http://localhost:4200`. |
| `BFF_HTTP_CONNECT_TIMEOUT` | Tiempo máximo de conexión. Por defecto: `3s`. |
| `BFF_HTTP_READ_TIMEOUT` | Tiempo máximo de espera de lectura. Por defecto: `10s`. |
| `API_DOCS_ENABLED` | Habilita Swagger y OpenAPI. Por defecto: `false`. |

Las URLs base de los microservicios no deben incluir `/api/v1`, porque los clientes HTTP del BFF agregan esa ruta.

El archivo `.env.example` de la raíz sirve como referencia. Spring Boot no lo carga automáticamente al ejecutar `spring-boot:run`.

Copiarlo a un archivo `.env` tampoco configura por sí solo esta ejecución. En PowerShell podemos definir las variables con `$env:`.

Los valores genéricos de issuer y audience incluidos como respaldo en la configuración no representan el tenant real del equipo.

## Ejecutar las pruebas

Desde la carpeta del BFF:

```powershell
.\mvnw.cmd clean test
```

Estas pruebas utilizan solicitudes simuladas, respuestas HTTP controladas y un emisor JWT local de pruebas. No necesitan que Oracle, los microservicios o el tenant real estén funcionando.

El resultado esperado es `BUILD SUCCESS`.

La cantidad de pruebas puede aumentar a medida que avance el proyecto.

## Ejecutar localmente para revisar salud y Swagger

```powershell
$env:API_DOCS_ENABLED = "true"
.\mvnw.cmd spring-boot:run
```

Con el puerto predeterminado:

- Salud: http://localhost:8080/actuator/health
- Swagger UI: http://localhost:8080/swagger-ui/index.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

En otra terminal se puede comprobar la salud:

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/actuator/health"
```

La respuesta esperada contiene:

```json
{
  "status": "UP"
}
```

Esta comprobación solamente confirma la salud del BFF. No demuestra que Orders, Catalog u Oracle estén disponibles.

Para detener la aplicación, utilizar `Ctrl + C`.

## Configurar la integración real

Antes de ejecutar estos comandos, reemplazar los valores de ejemplo por los datos acordados con el encargado de Entra ID:

```powershell
$env:JWT_ISSUER_URI = "https://login.microsoftonline.com/TU_TENANT_ID/v2.0"
$env:JWT_AUDIENCE = "AUDIENCE_ACORDADA_PARA_LA_API"
$env:ORDERS_SERVICE_URL = "http://localhost:8081"
$env:CATALOG_SERVICE_URL = "http://localhost:8082"
$env:ALLOWED_ORIGINS = "http://localhost:4200"
$env:API_DOCS_ENABLED = "true"

.\mvnw.cmd spring-boot:run
```

Estas variables se aplican a la sesión actual de PowerShell. Si se cambian con la aplicación ejecutándose, hay que reiniciarla.

Para probar autenticación en Postman:

1. Obtener un Access Token de Entra ID destinado a nuestra API.
2. Configurar Authorization como Bearer Token.
3. Ejecutar `GET http://localhost:8080/api/v1/auth/me`.
4. Comprobar que se reciben los datos del usuario, sus roles y sus scopes.

No utilizar el ID Token como credencial de la API.

## Scope y roles

Todas las operaciones de negocio implementadas requieren el scope:

```text
pedidos360.access
```

Spring lo representa internamente como:

```text
SCOPE_pedidos360.access
```

Los roles utilizados son:

```text
ADMIN
OPERADOR
CLIENTE
AUDITOR
```

Los valores de los roles deben coincidir con los configurados en Entra ID.

Las reglas por endpoint están descritas en el [contrato público de la API](../docs/CONTRATO_API.md).

Tener un rol no reemplaza el scope requerido.

## Endpoints implementados

| Grupo | Rutas |
|---|---|
| Usuario autenticado | `GET /api/v1/auth/me` |
| Pedidos | Listar, consultar, crear, actualizar estado y cancelar. |
| Catálogo | Listar, consultar, crear, modificar, actualizar stock y desactivar. |
| Salud | `GET /actuator/health`, sin token. |

Los clientes solamente pueden consultar o cancelar sus propios pedidos.

Al crear un pedido, el BFF obtiene `customerId` del JWT. El frontend no debe enviarlo.

## Swagger

La documentación se encuentra desactivada por defecto.

Con `API_DOCS_ENABLED=true`, Swagger UI y OpenAPI permiten acceso sin token. Esto no elimina la seguridad de los endpoints de negocio.

En el botón `Authorize` se debe ingresar el Access Token sin escribir el prefijo `Bearer`.

La configuración no conserva la autorización entre recargas.

Con `API_DOCS_ENABLED=false`, las rutas de documentación quedan bloqueadas incluso para un administrador.

Mantener la documentación desactivada en el despliegue salvo que el equipo acuerde expresamente habilitarla y controlar su exposición.

## Errores

El BFF utiliza los siguientes códigos principales:

| Código | Significado |
|---|---|
| `400` | Datos inválidos. |
| `401` | Token ausente o inválido. |
| `403` | Scope o rol insuficiente, o pedido de otro cliente. |
| `404` | Recurso no encontrado en el servicio. |
| `409` | Conflicto de estado o stock informado por el servicio. |
| `502` | Fallo de comunicación o respuesta no utilizable del microservicio. |
| `500` | Error interno inesperado del BFF. |

El detalle del intercambio con los microservicios está en el [contrato interno](../docs/CONTRATO_INTERNO_BFF_SERVICIOS.md).

## Seguridad del repositorio

No subir:

- Access Tokens ni ID Tokens.
- Contraseñas o client secrets.
- Credenciales de AWS u Oracle.
- Wallet de Oracle.
- Archivos locales con secretos.
- La carpeta `target`.

Los ejemplos de configuración deben utilizar valores de referencia, nunca credenciales reales.

## Pendientes de integración

- Confirmar los contratos con Orders y Catalog.
- Probar con tokens reales de Entra ID.
- Probar las llamadas desde Angular.
- Acordar e implementar las conexiones básicas de auditoría y reportería.
- Coordinar el contenedor del BFF con el encargado de infraestructura.
- Probar el flujo completo mediante AWS API Gateway.
