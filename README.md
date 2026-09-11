# Pedidos360

Proyecto desarrollado para la Evaluación Parcial 1 de la asignatura DSY1107 - Desarrollo Cloud Native I.

## Descripción

Pedidos360 es una solución orientada a microservicios para administrar pedidos, productos, stock, notificaciones, reportería y auditoría.

En esta primera etapa nos enfocaremos principalmente en implementar la autenticación, autorización y comunicación segura entre el frontend, API Gateway y el backend.

## Decisiones iniciales

- El equipo está compuesto por tres integrantes y fue autorizado por el docente.
- Se utilizará un solo repositorio con distintas carpetas para cada componente.
- Cada integrante trabajará en una rama propia.
- El frontend será desarrollado con Angular.
- La identidad será administrada con Microsoft Entra ID.
- La infraestructura principal estará en AWS.
- La base de datos será Oracle Cloud.
- Se implementará el rol `AUDITOR`.
- RabbitMQ y Kafka tendrán inicialmente un alcance básico y se ampliarán más adelante.
- El BFF volverá a validar el JWT aunque API Gateway ya lo haya validado.

## Arquitectura general

```text
Usuario
  |
  v
Frontend Angular
  |
  | Access Token
  v
AWS API Gateway
  |
  | JWT validado
  v
BFF Spring Boot
  |
  +------> Orders Service
  |
  +------> Catalog Service
  |
  +------> Audit Service
  |
  +------> Report Service
                 |
                 v
            Oracle Cloud
```

Microsoft Entra ID será responsable de autenticar a los usuarios y emitir los tokens.

AWS API Gateway será la entrada pública del sistema. El BFF y los demás microservicios no deberían quedar expuestos directamente a Internet.

## Tecnologías

### Frontend

- Angular 18+
- TypeScript
- MSAL Angular
- HTML y CSS

### Backend

- Java 21
- Spring Boot 3+
- Spring Security
- OAuth2 Resource Server
- Spring Data JPA
- OpenAPI/Swagger

### Infraestructura

- Microsoft Entra ID
- AWS API Gateway HTTP API
- AWS EC2
- Docker
- Docker Compose
- Oracle Cloud
- RabbitMQ
- Kafka y Zookeeper

## Estructura planificada

```text
pedidos360/
├── frontend-pedidos360/
├── ms-pedidos360-bff/
├── ms-pedidos360-orders/
├── ms-pedidos360-catalog/
├── ms-pedidos360-notify/
├── ms-pedidos360-report/
├── ms-pedidos360-audit/
├── infra/
│   ├── apps/
│   ├── mq/
│   ├── kafka/
│   └── aws/
├── docs/
├── postman/
├── .env.example
├── .gitignore
└── README.md
```

Las carpetas de código se crearán desde las ramas correspondientes. No es necesario crear carpetas vacías en `main`.

## Ramas de trabajo

| Rama | Responsable | Área |
|---|---|---|
| `feat/frontend-msal-rbac` | Integrante 1 | Angular, MSAL, guards, interceptor y vistas |
| `feat/bff-jwt-security` | Integrante 2 | BFF, validación JWT y autorización |
| `feat/services-aws-integration` | Integrante 3 | Microservicios, Oracle Cloud, Docker y AWS |
| `main` | Equipo completo | Código revisado e integrado |

Los nombres de los integrantes serán agregados cuando el equipo los defina.

## Roles

El sistema tendrá cuatro roles:

- `ADMIN`: administración general, catálogo y reportería.
- `OPERADOR`: gestión y actualización de pedidos.
- `CLIENTE`: creación y seguimiento de sus pedidos.
- `AUDITOR`: consulta de la trazabilidad del sistema.

La autorización será aplicada tanto en el frontend como en el backend. La validación del backend será la autorización definitiva.

## Flujo de desarrollo

1. Preparar documentación y contratos en `main`.
2. Crear las tres ramas desde el mismo punto.
3. Desarrollar los componentes en paralelo.
4. Integrar y probar localmente.
5. Fusionar los cambios mediante Pull Requests.
6. Desplegar la versión integrada en AWS.
7. Ejecutar pruebas completas contra el ambiente cloud.

## Documentación

- [Plan de trabajo](docs/PLAN_DE_TRABAJO.md)
- [Contrato inicial de la API](docs/CONTRATO_API.md)
- [Configuración y despliegue](docs/CONFIGURACION_Y_DESPLIEGUE.md)

## Reglas principales

- No desarrollar directamente en `main`.
- No subir credenciales ni tokens.
- Utilizar variables de entorno.
- Mantener los contratos acordados.
- Realizar Pull Requests pequeños y revisables.
- Comprobar que cada proyecto compile antes de fusionarlo.
- Mantener `main` estable.
