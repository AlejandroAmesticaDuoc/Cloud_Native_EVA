# Plan de trabajo de Pedidos360

## Objetivo del documento

Este documento define cómo dividiremos el desarrollo entre los tres integrantes del equipo.

La idea es que todos puedan avanzar en paralelo sin modificar los mismos archivos ni quedar bloqueados esperando que otra persona termine completamente su parte.

## Trabajo inicial del equipo

Antes de comenzar el desarrollo debemos acordar:

- Nombres de los integrantes.
- Roles del sistema.
- Scope utilizado por la API.
- Rutas iniciales.
- Formato de pedidos y productos.
- Formato de errores.
- Puertos locales.
- Variables de entorno.
- Criterios para aceptar un Pull Request.

Estos acuerdos quedarán registrados en `main`.

---

## Integrante 1: Frontend y MSAL

### Rama

```text
feat/frontend-msal-rbac
```

### Carpeta asignada

```text
/frontend-pedidos360
```

### Responsabilidades

- Crear el proyecto Angular.
- Instalar MSAL Browser y MSAL Angular.
- Configurar la conexión con Microsoft Entra ID.
- Implementar inicio de sesión.
- Implementar cierre de sesión.
- Manejar el retorno después del login.
- Obtener el Access Token.
- Implementar `MsalGuard`.
- Implementar un guard por roles.
- Configurar `MsalInterceptor`.
- Adjuntar el Access Token a las peticiones hacia API Gateway.
- Leer roles y scopes desde los claims.
- Manejar errores `401` y `403`.
- Crear las vistas iniciales:
  - Login.
  - Dashboard.
  - Pedidos.
  - Catálogo.
  - Reportería.
  - Auditoría.
- Mostrar u ocultar acciones dependiendo del rol.
- Crear servicios Angular para consumir la API.

### Trabajo que puede realizar sin esperar

Mientras Entra ID y el backend todavía no estén listos, puede:

- Crear el proyecto Angular.
- Crear las rutas.
- Crear las vistas.
- Crear interfaces TypeScript.
- Crear guards con datos simulados.
- Crear servicios HTTP apuntando a una URL configurable.
- Utilizar archivos JSON como respuestas temporales.

### Criterios de término

- Angular compila sin errores.
- Login y logout funcionan.
- Las rutas privadas exigen autenticación.
- Las rutas restringidas verifican roles.
- Se utiliza un Access Token.
- El interceptor solamente agrega tokens al dominio de API Gateway.
- El frontend maneja correctamente `401` y `403`.
- No existen secretos dentro del código.

---

## Integrante 2: BFF y seguridad

### Rama

```text
feat/bff-jwt-security
```

### Carpeta asignada

```text
/ms-pedidos360-bff
```

### Responsabilidades

- Crear el BFF con Spring Boot 3 y Java 21.
- Configurar Spring Security.
- Configurar OAuth2 Resource Server.
- Validar la firma del JWT mediante JWKS.
- Validar issuer.
- Validar audience.
- Validar expiración y vigencia.
- Leer roles y scopes.
- Convertir claims en autoridades Spring.
- Aplicar autorización por endpoint.
- Entregar respuestas `401` y `403`.
- Implementar clientes para comunicarse con Orders y Catalog.
- Mantener las URLs de los servicios en variables de entorno.
- Publicar documentación Swagger/OpenAPI.
- Crear pruebas automatizadas de seguridad.
- Evitar registrar tokens completos en los logs.

### Trabajo que puede realizar sin esperar

Mientras Entra y los microservicios todavía no estén listos, puede:

- Crear el proyecto Spring Boot.
- Crear `SecurityConfig`.
- Utilizar JWT simulados en las pruebas.
- Crear un endpoint protegido de prueba.
- Simular las respuestas de Orders y Catalog.
- Definir clientes HTTP mediante interfaces.
- Crear el formato común de errores.

### Pruebas mínimas

- Petición sin token.
- Token alterado.
- Token expirado.
- Issuer incorrecto.
- Audience incorrecta.
- Token válido sin rol.
- Token válido con rol autorizado.
- Scope insuficiente.
- Comunicación fallida con un microservicio.

### Criterios de término

- El proyecto compila.
- Todas las pruebas pasan.
- Las rutas privadas nunca permiten acceso anónimo.
- Se diferencia correctamente entre `401` y `403`.
- El BFF vuelve a validar el token recibido.
- Las reglas de autorización se encuentran documentadas.

---

## Integrante 3: Servicios e infraestructura

### Rama

```text
feat/services-aws-integration
```

### Carpetas asignadas

```text
/ms-pedidos360-orders
/ms-pedidos360-catalog
/ms-pedidos360-notify
/ms-pedidos360-report
/ms-pedidos360-audit
/infra
/postman
```

### Primera etapa

- Crear las aplicaciones de frontend y API en Entra ID.
- Crear los roles del sistema.
- Crear el scope de Pedidos360.
- Compartir los identificadores públicos con los demás integrantes.
- Preparar la conexión con Oracle Cloud.
- Crear Orders Service.
- Crear Catalog Service.
- Crear Dockerfiles.
- Crear Docker Compose.
- Preparar pruebas Postman.

### Orders Service

- Crear pedidos.
- Consultar pedidos.
- Consultar un pedido específico.
- Actualizar el estado de un pedido.
- Filtrar pedidos de un cliente.
- Validar las transiciones de estado.
- Solicitar el descuento de stock al aceptar un pedido.
- Persistir información en Oracle Cloud.

### Catalog Service

- Consultar productos.
- Crear productos.
- Modificar productos.
- Controlar el stock.
- Validar disponibilidad.
- Descontar stock al aceptar un pedido.
- Persistir información en Oracle Cloud.

### Alcance básico de mensajería

RabbitMQ y Kafka se implementarán después de completar el flujo principal de seguridad.

Inicialmente se considera:

#### RabbitMQ

- Una cola de notificaciones:

```text
q.cmd.email
```

- Una cola de mensajes fallidos:

```text
q.cmd.email.dlq
```

- Publicar una notificación cuando cambie el estado de un pedido.

#### Kafka

- Un tópico principal:

```text
orders.events
```

- Publicar eventos básicos:
  - `OrderCreated`
  - `OrderAccepted`
  - `OrderStatusChanged`
  - `OrderCancelled`

- Audit Service consumirá los eventos para registrar la trazabilidad.
- Report Service podrá consumirlos posteriormente para generar estadísticas.

### AWS

Después de completar la integración local:

- Crear o configurar las instancias EC2.
- Desplegar los contenedores.
- Configurar AWS API Gateway HTTP API.
- Crear JWT Authorizer.
- Configurar issuer y audience.
- Configurar CORS.
- Crear rutas hacia el BFF.
- Verificar que los microservicios no queden expuestos directamente.
- Ejecutar las pruebas Postman en AWS.

### Criterios de término

- Orders y Catalog compilan.
- Los microservicios se conectan a Oracle Cloud.
- Las reglas de negocio funcionan.
- Docker Compose levanta las aplicaciones.
- API Gateway valida el JWT.
- Las peticiones autorizadas llegan al BFF.
- Las pruebas de integración pasan.

---

## Forma de trabajar en paralelo

### Punto de control 1

Cada integrante debe lograr:

- Frontend: Angular funcionando con las vistas iniciales.
- BFF: endpoint protegido funcionando con JWT simulado.
- Servicios: Orders y Catalog levantados con endpoints básicos.
- Entra: aplicaciones, roles y scope creados.

### Punto de control 2

Integración local:

```text
Angular
  -> BFF
  -> Orders/Catalog
  -> Oracle Cloud
```

### Punto de control 3

Integración cloud:

```text
Angular
  -> AWS API Gateway
  -> BFF en EC2
  -> Microservicios en EC2
  -> Oracle Cloud
```

---

## Pull Requests

Cada Pull Request debe incluir:

- Descripción de lo realizado.
- Instrucciones para probar.
- Evidencia de compilación.
- Pruebas ejecutadas.
- Variables nuevas agregadas.
- Cambios realizados en el contrato.
- Confirmación de que no se agregaron secretos.

## Orden inicial de integración

1. `feat/bff-jwt-security`
2. `feat/services-aws-integration`
3. `feat/frontend-msal-rbac`
4. Correcciones mediante ramas `fix/*`

El orden puede cambiar si un componente termina antes, siempre que el Pull Request compile y no rompa `main`.
