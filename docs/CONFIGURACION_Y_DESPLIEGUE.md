# Configuración y despliegue

## Distribución de servicios

### Ambiente local

```text
Frontend:  http://localhost:4200
BFF:       http://localhost:8080
Orders:    http://localhost:8081
Catalog:   http://localhost:8082
Notify:    http://localhost:8083
Report:    http://localhost:8084
Audit:     http://localhost:8085
```

Los servicios se ejecutarán localmente mediante Docker cuando sea posible.

La base de datos será PostgreSQL. Para desarrollar se ejecutará en un contenedor local. Docker ejecuta el motor, no lo reemplaza.

## PostgreSQL

Cada microservicio con persistencia utilizará una base y un usuario propios, configurados mediante variables de entorno. El primer componente preparado es Catalog.

Datos necesarios:

- URL JDBC.
- Usuario.
- Contraseña.
- Certificado de confianza si la conexión cloud lo requiere.

Las credenciales nunca deben subirse a GitHub. Catalog usa `CATALOG_DB_URL`, `CATALOG_DB_USERNAME` y `CATALOG_DB_PASSWORD`. La plantilla de variables está en `.env.example`.

La preparación paso a paso está en [PostgreSQL local](POSTGRESQL_LOCAL.md). No hay que instalar el motor directamente en Windows si se utiliza Docker Desktop.

Ejemplo conceptual:

```text
Catalog ejecutado desde VS Code / Maven
        |
        v
PostgreSQL local en Docker (127.0.0.1:5432)
        |
        v
Volumen persistente de datos
```

## Microsoft Entra ID

Se utilizarán dos registros de aplicación:

Estos dos registros corresponden al frontend y la API. Orders agrega un tercero para su identidad técnica de comunicación con Catalog, sin login interactivo; ver [Orders completo](ORDERS_COMPLETO.md).

### Aplicación frontend

Tipo:

```text
Single Page Application
```

Responsabilidades:

- Inicio de sesión.
- Cierre de sesión.
- Obtención del Access Token.
- Redirección a Angular.

Redirect inicial:

```text
http://localhost:4200
```

### Aplicación API/BFF

Responsabilidades:

- Representar la API protegida.
- Exponer el scope de Pedidos360.
- Definir los roles.
- Generar la audience esperada por el BFF y API Gateway.

## Valores compartidos

Los siguientes datos no son secretos, pero deben mantenerse configurables:

```text
Tenant ID
SPA Client ID
API Client ID
Authority
Issuer
Audience
Scope
```

Los siguientes datos sí son secretos:

```text
Contraseñas
Client secrets
Tokens
Credenciales de AWS
Credenciales de PostgreSQL
```

## Desarrollo local

El primer flujo será:

```text
Angular local
  -> BFF local
  -> Orders y Catalog locales
  -> PostgreSQL local
```

El login de Entra puede utilizarse desde localhost siempre que la URL esté registrada como redirect URI.

## Docker Compose

`compose.postgres.yml` levanta la base local. Combinándolo con `compose.catalog.yml` se ejecutan PostgreSQL, Catalog y BFF en una misma red de desarrollo. La preparación y los comandos están en [Catalog completo](CATALOG_COMPLETO.md).

Agregar `-f compose.orders.yml` al mismo comando incorpora Orders y su propia base PostgreSQL en `postgres-orders:5432`, publicada localmente en el puerto 5433. No modifica ni reinicializa la base existente de Catalog.

El despliegue completo sigue pendiente de infraestructura. La solución planificada incluye:

- BFF.
- Orders.
- Catalog.
- PostgreSQL, si se decide administrarlo en contenedor en ese ambiente.
- Notify y Mailpit para probar correos localmente.
- RabbitMQ.
- Kafka.
- Zookeeper.
- Posteriormente Report y Audit.

Las URLs internas utilizarán el nombre del servicio Docker.

Ejemplo:

```text
http://orders:8081
http://catalog:8082
```

No se deben escribir direcciones IP fijas en el código.

Para PostgreSQL, una aplicación ejecutada en Windows usa `localhost`; Catalog dentro de Docker usa `postgres:5432` y Orders usa `postgres-orders:5432`. Los archivos locales deben combinarse en un solo comando, no iniciarse como proyectos separados. Para incluir RabbitMQ, Notify y Mailpit se agrega un cuarto argumento: `-f compose.notify.yml`. Las variables y pruebas están en [Notificaciones](NOTIFY_RABBITMQ.md). Kafka, Audit y Report aún no están incluidos.

## Despliegue AWS

Una vez que la integración local funcione:

Primero se debe acordar dónde alojar PostgreSQL en AWS. Puede ser un servicio administrado o una instalación gestionada por el equipo; no se ha creado ni contratado ninguno. Deben revisarse los costos, las copias de seguridad y el acceso privado antes de desplegar.

El puerto 5432 no debe quedar abierto a Internet. Las conexiones cloud deben verificar el certificado del servidor (por ejemplo, `sslmode=verify-full` en la URL JDBC, con la CA correspondiente). No reutilizar contraseñas locales. La aplicación no debe utilizar un superusuario.

1. Compilar los proyectos.
2. Crear las imágenes Docker.
3. Publicar las imágenes en un registro.
4. Crear o configurar EC2.
5. Ejecutar las aplicaciones mediante Docker Compose.
6. Configurar AWS API Gateway HTTP API.
7. Crear JWT Authorizer.
8. Configurar issuer y audience.
9. Crear rutas hacia el BFF.
10. Configurar CORS.
11. Ejecutar pruebas de seguridad y comunicación.

## Flujo en AWS

```text
Frontend
  -> Microsoft Entra ID
  -> AWS API Gateway
  -> BFF en EC2
  -> Microservicios en EC2
  -> PostgreSQL en AWS (alojamiento por definir)
```

## CORS

### Desarrollo

Origen permitido:

```text
http://localhost:4200
```

Headers:

```text
Authorization
Content-Type
```

Métodos iniciales:

```text
GET
POST
PUT
PATCH
DELETE
OPTIONS
```

La petición `OPTIONS` no debe exigir autenticación.

### Producción

En producción se reemplazará localhost por el dominio real del frontend.

No se utilizará `*` como origen en producción.

## Pruebas antes de desplegar

- Todos los proyectos compilan.
- Docker Compose levanta los servicios.
- El frontend puede iniciar sesión.
- El BFF acepta un token correcto.
- El BFF rechaza un token incorrecto.
- Orders y Catalog se conectan a sus bases PostgreSQL y ejecutan las migraciones.
- Las credenciales no aparecen en Git.
- Las rutas coinciden con el contrato.
- Los errores entregan códigos correctos.

## Pruebas después de desplegar

- API Gateway responde `401` sin token.
- API Gateway responde `401` con token inválido.
- El BFF responde `403` cuando falta el rol.
- Un usuario autorizado puede acceder.
- API Gateway enruta correctamente al BFF.
- El BFF puede comunicarse con los microservicios.
- Los microservicios pueden comunicarse con PostgreSQL usando conexiones seguras.
