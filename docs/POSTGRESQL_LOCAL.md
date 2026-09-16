# PostgreSQL local para Pedidos360

## Objetivo de este paso

Vamos a ejecutar PostgreSQL en nuestro computador para desarrollar sin depender de una cuenta cloud. Esta guía prepara Catalog, que ya incluye CRUD, seguridad y operaciones internas de stock. Orders utiliza otra base y usuario, descritos en la [guía de Orders](ORDERS_COMPLETO.md).

Este cambio no modifica las rutas ni los DTO que consume el BFF. El cambio de motor debe informarse al docente; no estamos dando por aprobada una modificación de la pauta.

## Programas necesarios

- VS Code y GitHub Desktop, que ya utilizamos.
- Java compatible con el proyecto, que compila para Java 21.
- Docker Desktop con el motor iniciado y usando contenedores Linux.
- Postman para probar salud, CRUD y permisos.
- Node 22 o superior, solo para el script de integración completa.

No necesitamos XAMPP, una instalación local de PostgreSQL ni una cuenta de un proveedor cloud para este paso. Un cliente gráfico SQL es opcional; también podemos usar `psql` dentro del contenedor.

Para instalar Docker Desktop, seguir la [guía oficial para Windows](https://docs.docker.com/desktop/setup/install/windows-install/), incluyendo sus requisitos de WSL 2 y virtualización. Después de instalarlo puede ser necesario reiniciar Windows y abrir una terminal nueva.

Comprobar en PowerShell:

```powershell
docker version
docker compose version
```

`docker version` debe mostrar información del cliente y del servidor. Si el comando no existe o no logra conectarse al motor, primero hay que resolver Docker Desktop.

Si Docker se instaló por usuario y una terminal antigua no encuentra el comando o `docker-credential-desktop`, abrir una terminal nueva. Como alternativa temporal, después de comprobar que existe esa carpeta:

```powershell
$env:PATH = "$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin;" + $env:PATH
```

## 1. Definir las credenciales locales

Trabajar en la rama `feat/services-aws-integration`, desde la raíz del repositorio:

```powershell
Set-Location -LiteralPath 'D:\Cloud_Native_EVA'
git branch --show-current

$env:POSTGRES_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new(
    '', (Read-Host 'Define la contraseña de administración de PostgreSQL' -AsSecureString)
).Password

$env:CATALOG_DB_PASSWORD = [System.Net.NetworkCredential]::new(
    '', (Read-Host 'Define otra contraseña para el usuario de Catalog' -AsSecureString)
).Password

$env:CATALOG_DB_USERNAME = 'pedidos360_catalog'
$env:CATALOG_DB_URL = 'jdbc:postgresql://localhost:5432/pedidos360_catalog'
```

Usar contraseñas distintas y guardarlas en un gestor seguro. No quedan escritas como texto literal en el historial de comandos, pero los procesos necesitan recibirlas como variables de entorno. No publicar capturas de esas variables ni ejecutar comandos que las impriman.

Estas variables duran solamente en esta terminal. Si cerramos PowerShell, hay que volver a definirlas con las MISMAS contraseñas: cambiar una variable no cambia la contraseña ya guardada dentro de PostgreSQL.

`.env.example` es una plantilla, no una configuración activa. Docker Compose puede leer un `.env` local; Spring Boot ejecutado con Maven no lo carga automáticamente. En esta guía usamos variables de PowerShell para que ambos reciban los mismos valores. No es necesario crear `.env`.

## 2. Levantar PostgreSQL

En la misma terminal:

```powershell
docker compose -f compose.postgres.yml up -d --wait
docker compose -f compose.postgres.yml ps
```

El servicio `postgres` debe aparecer saludable. La primera ejecución descarga la imagen y prepara una base vacía, así que puede demorar más.

La configuración utiliza:

| Elemento | Valor |
|---|---|
| Imagen | `postgres:17.11-alpine` |
| Dirección local | `127.0.0.1:5432` |
| Base de Catalog | `pedidos360_catalog` |
| Usuario de Catalog | `pedidos360_catalog` |
| Usuario administrador | `postgres`, no utilizado por Spring Boot |
| Persistencia | Volumen Docker `postgres_data` dentro del proyecto Compose |

El script de `infra/postgres` crea el usuario de Catalog sin permisos de superusuario ni para crear otras bases o roles. Catalog es propietario únicamente de su base y ejecuta sus migraciones; cuando preparemos el despliegue revisaremos la separación de credenciales de migración y ejecución.

Si 5432 está ocupado, sin detener ni borrar otra base, elegir un puerto libre y cambiar también la URL de Catalog:

```powershell
$env:POSTGRES_PORT = '5433'
$env:CATALOG_DB_URL = 'jdbc:postgresql://localhost:5433/pedidos360_catalog'
docker compose -f compose.postgres.yml up -d --wait
```

## 3. Ejecutar Catalog

Conservar la misma terminal para no perder las variables. Para utilizar los endpoints protegidos, configurar los mismos valores reales que utiliza el BFF; reemplazar los marcadores antes de ejecutar:

```powershell
$env:JWT_ISSUER_URI = 'https://login.microsoftonline.com/<tenant-id>/v2.0'
$env:JWT_AUDIENCE = '<api-client-id>'
```

Para comprobar salud no se necesita un token. Si aún no está listo Entra, usar las pruebas aisladas del apartado siguiente para verificar la integración sin desactivar la seguridad.

Iniciar Catalog:

```powershell
Set-Location -LiteralPath 'D:\Cloud_Native_EVA\ms-pedidos360-catalog'
.\mvnw.cmd spring-boot:run
```

Al iniciar, Flyway ejecuta las migraciones pendientes: `V1` crea productos y `V2` registra descuentos de stock por pedido. Después Hibernate valida el esquema. En los siguientes arranques Flyway no vuelve a crear las tablas ni borra productos.

En Postman comprobar:

```http
GET http://localhost:8082/actuator/health
GET http://localhost:8082/actuator/health/readiness
```

Ambas respuestas deben indicar `UP`. Readiness incluye la conexión a la base. Los detalles internos siguen ocultos.

Si falta la contraseña, PostgreSQL no está disponible o falla la migración, el arranque no se debe considerar exitoso. No hay un reemplazo automático por una base en memoria en la ejecución normal.

El CRUD está disponible en `/api/v1/catalog`. Sin JWT responde `401`; un JWT válido sin los permisos necesarios recibe `403`. Revisar el [contrato y la colección Postman](CATALOG_COMPLETO.md) para probarlo a través del BFF.

## 4. Consultar la tabla

Después de iniciar Catalog, abrir otra terminal en la raíz. Si no tiene las variables, volver a definir las dos contraseñas del paso 1; Compose las necesita para resolver su configuración.

```powershell
Set-Location -LiteralPath 'D:\Cloud_Native_EVA'
docker compose -f compose.postgres.yml exec postgres psql -h 127.0.0.1 -U pedidos360_catalog -d pedidos360_catalog -W
```

Ingresar la contraseña de Catalog cuando `psql` la solicite. Dentro de `psql`:

```sql
SELECT current_database(), current_user;
SELECT id, name, price, stock, active, version FROM products ORDER BY id;
```

Al principio no habrá productos. Para salir de `psql`, escribir `\q`.

## 5. Ejecutar las pruebas

Desde la carpeta de Catalog:

```powershell
.\mvnw.cmd clean test
```

Estas 69 pruebas no necesitan Docker ni contraseñas. Verifican API, validaciones, JWT, OpenAPI y persistencia. H2 se utiliza exclusivamente en el classpath de test, con las mismas migraciones SQL; no demuestra por sí solo compatibilidad con PostgreSQL.

Con Docker Desktop iniciado, ejecutar también:

```powershell
.\mvnw.cmd clean verify -Ppostgres-it
```

El perfil agrega 47 pruebas de `ProductPostgresIT` y `CatalogPostgresApiIT`. Testcontainers levanta PostgreSQL temporal, sin utilizar la base de desarrollo, y lo elimina al finalizar. Verifica persistencia, permisos SQL, CRUD, rollback e idempotencia ante solicitudes concurrentes. Si Docker no está disponible, la integración falla en vez de omitirse silenciosamente.

El perfil Maven `postgres-it` no es un perfil de ejecución de Spring. No hay que usarlo en `spring-boot:run`.

### Integración HTTP con el BFF

Compilar ambos JAR y ejecutar el script desde la raíz, con Docker iniciado:

```powershell
Set-Location -LiteralPath 'D:\Cloud_Native_EVA\ms-pedidos360-bff'
.\mvnw.cmd clean verify
Set-Location -LiteralPath 'D:\Cloud_Native_EVA\ms-pedidos360-catalog'
.\mvnw.cmd clean verify -Ppostgres-it
Set-Location -LiteralPath 'D:\Cloud_Native_EVA'
node scripts/test-bff-catalog.mjs
```

El script inicia ambos JAR en puertos libres, una base PostgreSQL temporal y un emisor local de JWT firmado. Comprueba 26 casos, incluyendo permisos, stock, persistencia y la respuesta `502` cuando Catalog se detiene. Al terminar retira sus procesos y su base temporal; no mantiene los servicios levantados ni modifica tus datos. No publica tokens ni guarda credenciales. Esto no sustituye la prueba final con usuarios reales de Entra.

## Datos y contraseñas

- El volumen conserva los datos aunque el contenedor se detenga o se vuelva a crear.
- El script `001-create-catalog.sh` solo se ejecuta con un volumen vacío.
- Cambiar `CATALOG_DB_PASSWORD` o el script después del primer arranque NO modifica usuarios existentes.
- Ante un error de inicialización, revisar los logs antes de reintentar. No borrar el volumen como solución automática.
- No utilizar `down -v`, `docker volume rm` ni operaciones de limpieza que eliminen el volumen si hay datos que conservar.
- Cuando una migración ya fue aplicada, agregar una nueva versión. El siguiente cambio será `V3__...sql`; no modificar `V1` ni `V2` sobre una base que ya las ejecutó.

Para detener solamente esta base sin borrar datos:

```powershell
Set-Location -LiteralPath 'D:\Cloud_Native_EVA'
docker compose -f compose.postgres.yml stop
```

## Paso posterior: AWS

Este Compose es de desarrollo local, no una configuración de producción. El alojamiento definitivo, los respaldos y las redes privadas se coordinarán con infraestructura. No se ha creado ningún recurso cloud.

Las aplicaciones desplegadas deberán usar su URL JDBC real, credenciales diferentes y TLS con verificación del servidor. No se debe exponer el puerto de PostgreSQL a Internet ni reutilizar el usuario de Catalog en otros microservicios.

## Referencias técnicas

- [Versiones mantenidas de PostgreSQL](https://www.postgresql.org/support/versioning/).
- [Imagen oficial PostgreSQL: variables, inicialización y volúmenes](https://github.com/docker-library/docs/blob/master/postgres/README.md).
- [Migraciones de base de datos en Spring Boot](https://docs.spring.io/spring-boot/how-to/data-initialization.html).
- [PostgreSQL con Testcontainers](https://java.testcontainers.org/modules/databases/postgres/).
