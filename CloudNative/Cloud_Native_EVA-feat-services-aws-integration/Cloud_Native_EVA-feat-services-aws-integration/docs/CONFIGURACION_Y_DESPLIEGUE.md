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

La base de datos estará en Oracle Cloud. Docker se utilizará para ejecutar las aplicaciones, pero no reemplaza la base de datos.

## Oracle Cloud

Orders y Catalog se conectarán a Oracle Cloud mediante variables de entorno.

Datos necesarios:

- URL JDBC.
- Usuario.
- Contraseña.
- Wallet, si la conexión lo requiere.
- Ruta configurada en `TNS_ADMIN`.

El Wallet y las credenciales nunca deben subirse a GitHub.

Cuando se utilice Docker, el Wallet podrá montarse como un volumen de solo lectura.

Ejemplo conceptual:

```text
Archivo Wallet en el computador
        |
        | volumen de solo lectura
        v
Contenedor Spring Boot
        |
        v
Oracle Cloud
```

## Microsoft Entra ID

Se utilizarán dos registros de aplicación:

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
Credenciales de Oracle
Wallet de Oracle
```

## Desarrollo local

El primer flujo será:

```text
Angular local
  -> BFF local
  -> Orders y Catalog locales
  -> Oracle Cloud
```

El login de de Entra puede utilizarse desde localhost siempre que la URL esté registrada como redirect URI.

## Docker Compose

Docker Compose se utilizará para levantar:

- BFF.
- Orders.
- Catalog.
- Posteriormente Notify.
- RabbitMQ.
- Kafka.
- Zookeeper.
- Posteriormente Report y Audit.

Las URLs internas utilizarán el nombre del servicio Docker.

Ejemplo:

```text
http://orders-service:8081
http://catalog-service:8082
```

No se deben escribir direcciones IP fijas en el código.

## Despliegue AWS

Una vez que la integración local funcione:

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
  -> Oracle Cloud
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
- Orders y Catalog se conectan a Oracle Cloud.
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
- Los microservicios pueden comunicarse con Oracle Cloud.
