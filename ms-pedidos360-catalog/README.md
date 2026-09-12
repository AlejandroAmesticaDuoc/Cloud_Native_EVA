# MS Pedidos360 Catalog

Microservicio de catálogo de productos. Utiliza Spring Boot 4.1.1, Java 21 como objetivo de compilación, Spring Data JPA y PostgreSQL.

## Estado de este bloque

Implementado:

- DTO compatibles con el contrato del BFF y validaciones de entrada.
- Endpoint de salud en el puerto 8082.
- Configuración de conexión PostgreSQL mediante variables de entorno.
- Migración Flyway para la tabla `products`.
- Entidad `Product` y repositorio `ProductRepository`.
- Consultas de productos activos, desactivación lógica y versión para detectar actualizaciones concurrentes.
- Pruebas rápidas con H2 y contrato de integración con PostgreSQL real mediante Testcontainers.

Pendiente: controladores CRUD, capa de servicio, manejo de errores, seguridad e integración HTTP real con el BFF y Orders. No publicar este microservicio en Internet mientras se implementa ese trabajo.

## Estructura

```text
src/main/java/cl/duoc/pedidos360/catalog/
  dto/                  Contrato de entrada y salida
  entity/Product.java   Mapeo de la tabla de productos
  repository/           Consultas JPA
src/main/resources/
  application.properties
  db/migration/V1__create_products.sql
src/test/java/cl/duoc/pedidos360/catalog/
  config/               Pruebas de salud
  dto/                  Pruebas de validación
  repository/           Contrato de persistencia en H2 y PostgreSQL
src/test/resources/
  application-test.properties
```

## Ejecutar localmente

Seguir primero la [guía de PostgreSQL local](../docs/POSTGRESQL_LOCAL.md). Allí se explica cómo iniciar Docker, definir las contraseñas y levantar la base.

Catalog necesita estas variables en la misma terminal donde se inicia Maven:

| Variable | Valor predeterminado / uso |
|---|---|
| `CATALOG_PORT` | `8082` |
| `CATALOG_DB_URL` | `jdbc:postgresql://localhost:5432/pedidos360_catalog` |
| `CATALOG_DB_USERNAME` | `pedidos360_catalog` |
| `CATALOG_DB_PASSWORD` | Obligatoria, sin valor predeterminado |

```powershell
Set-Location -LiteralPath 'D:\Cloud_Native_EVA\ms-pedidos360-catalog'
.\mvnw.cmd spring-boot:run
```

El archivo `.env.example` no se carga automáticamente al ejecutar Maven. No escribir credenciales en `application.properties` ni en los scripts SQL.

Salud: `GET http://localhost:8082/actuator/health`.
Disponibilidad con base de datos: `GET http://localhost:8082/actuator/health/readiness`.

## Persistencia

Cada producto tiene `id`, `name`, `price`, `stock`, `active` y una `version` interna. La versión no cambia el DTO público.

El precio se guarda como `NUMERIC(12,2)`, con hasta diez dígitos enteros y dos decimales. El stock no puede ser negativo. Desactivar cambia `active` a `false`; no elimina la fila.

Flyway es el único encargado de crear y modificar tablas. Hibernate tiene `ddl-auto=validate`: comprueba el esquema, pero no lo modifica automáticamente. Las nuevas modificaciones del esquema deben agregarse como nuevas migraciones.

## Pruebas

Rápidas, sin Docker:

```powershell
.\mvnw.cmd clean test
```

Integración adicional con PostgreSQL temporal, requiere Docker:

```powershell
.\mvnw.cmd clean verify -Ppostgres-it
```

H2 está limitado a dependencias y recursos de test; no reemplaza PostgreSQL en la aplicación ni se incluye en el JAR final. La validación definitiva de compatibilidad con PostgreSQL es la segunda ejecución.

## Coordinación con el equipo

- Rama: `feat/services-aws-integration`.
- La persona encargada del BFF desarrolla este microservicio y PostgreSQL.
- Infraestructura coordina el despliegue y las redes en AWS.
- El BFF conserva sus DTO, rutas y reglas JWT actuales. No accede directamente a esta base.
