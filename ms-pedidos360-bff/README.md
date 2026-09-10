# MS Pedidos360 BFF

Backend for Frontend de la plataforma Pedidos360.

## Responsabilidad

Este componente será la entrada al backend después de AWS API Gateway.

Sus responsabilidades principales son:

- Validar los tokens JWT emitidos por Microsoft Entra ID.
- Validar issuer, audience, firma y vigencia.
- Leer roles y scopes.
- Autorizar las peticiones según el rol del usuario.
- Entregar respuestas 401 y 403 correctamente.
- Comunicarse con los microservicios internos.
- Exponer una API única para el frontend.

## Tecnologías

- Java 21
- Spring Boot 4.1.1
- Spring Security
- OAuth2 Resource Server
- Maven
- JWT
- OpenAPI

## Puerto local

```text
8080