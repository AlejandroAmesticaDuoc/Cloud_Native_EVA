# Pedidos360

Proyecto desarrollado para la Evaluación Parcial 1 de la asignatura DSY1107 - Desarrollo Cloud Native I.

## Descripción

Pedidos360 es una solución orientada a microservicios para administrar pedidos, productos, stock, notificaciones, reportería y auditoría.

En esta primera etapa nos enfocaremos principalmente en implementar la autenticación, autorización y comunicación segura entre el frontend, API Gateway y el backend.

## Decisiones iniciales

- El equipo está compuesto por tres integrantes y fue autorizado por el docente.
- Se utilizará un solo repositorio con distintas carpetas para cada componente.
- Se mantienen las tres ramas acordadas; servicios e infraestructura coordinan carpetas en su rama compartida.
- El frontend será desarrollado con Angular.
- La identidad será administrada con Microsoft Entra ID.
- La infraestructura principal estará en AWS.
- La base de datos será PostgreSQL. Primero se ejecutará localmente en Docker y luego se definirá su alojamiento en AWS.
- Se implementará el rol `AUDITOR`.
- RabbitMQ y Kafka tendrán inicialmente un alcance básico y se ampliarán más adelante. En la EP3 RabbitMQ se amplió con comandos de correo, tickets de cocina y boletas, DLQ, auditoría y un microservicio administrador ([RabbitMQ en Pedidos360](docs/RABBITMQ.md)).
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
            PostgreSQL
```

Microsoft Entra ID será responsable de autenticar a los usuarios y emitir los tokens.

Cada microservicio con persistencia tendrá su propia base y usuario. No se consultarán directamente las tablas de otro servicio. El BFF no se conecta a la base de datos.

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
- PostgreSQL 17
- RabbitMQ
- Kafka en modo KRaft, sin ZooKeeper

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
├── ms-pedidos360-mq-admin/
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
| `feat/services-aws-integration` | Integrantes 2 y 3 | Integrante 2: microservicios y PostgreSQL. Integrante 3: infraestructura y AWS. |
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
- [Preparar PostgreSQL local](docs/POSTGRESQL_LOCAL.md)
- [Estado y ejecución de Catalog](ms-pedidos360-catalog/README.md)
- [Contrato de Catalog, stock interno y pruebas con el BFF](docs/CATALOG_COMPLETO.md)
- [Orders, estados, stock e identidad técnica](docs/ORDERS_COMPLETO.md)
- [RabbitMQ en Pedidos360: topología, DLQ, mq-admin y demostración EP3](docs/RABBITMQ.md)
- [RabbitMQ, Notify y correo local](docs/NOTIFY_RABBITMQ.md)
- [Administrador de RabbitMQ (mq-admin)](ms-pedidos360-mq-admin/README.md)
- [Kafka y contrato de eventos](docs/KAFKA_EVENTOS.md)
- [Audit, historial y consultas protegidas](docs/AUDIT_COMPLETO.md)
- [Report, ventas por hora y tiempos de entrega](docs/REPORT_COMPLETO.md)

## Cambio de base de datos y estado actual

Se decidió utilizar PostgreSQL por los problemas para habilitar la cuenta del proveedor anterior. Este cambio debe informarse al docente; no se da por aprobada una excepción a la pauta.

Catalog cuenta con CRUD, validaciones, JWT y stock transaccional. Orders crea y consulta pedidos, verifica propiedad, aplica estados y coordina descuentos y devoluciones con una identidad de servicio. Se verificó BFF → Orders → Catalog → PostgreSQL, incluyendo reintentos después de una respuesta perdida y un reinicio. Orders también publica avisos en RabbitMQ y eventos en Kafka mediante registros pendientes en su base. Notify envía los avisos por SMTP a un buzón local de demostración. Audit conserva el historial para ADMIN y AUDITOR. Report procesa Kafka con otro grupo, mantiene su propia base y entrega a ADMIN el resumen de estados, montos entregados por hora y lead time. Siguen pendientes las pruebas con frontend, Entra real y AWS.

El archivo `compose.postgres.yml` inicia la base de Catalog. Al combinarlo con `compose.catalog.yml` se agregan Catalog y BFF; `compose.orders.yml` incorpora Orders y su propia base. `compose.rabbitmq.yml` agrega RabbitMQ y mq-admin (y se puede levantar solo); `compose.notify.yml` agrega Notify y Mailpit y se usa junto a `compose.rabbitmq.yml`; `compose.kafka.yml` incorpora Kafka y el inicializador del tópico. `compose.audit.yml` y `compose.report.yml` añaden sus servicios y bases PostgreSQL, y `compose.commands.yml` activa los consumidores RabbitMQ de Catalog, Report y Audit. Usar los nueve archivos con `-f`, en ese orden y en un solo comando, para la solución local completa del backend (ver la sección RabbitMQ). No despliegan en AWS. H2 se utiliza solamente en tests; las integraciones utilizan PostgreSQL real con Docker.

El proyecto activo está en la raíz. La carpeta `CloudNative` conserva una copia antigua del trabajo del equipo, no es una segunda configuración vigente y no debe usarse para ejecutar estos pasos.

## Servicios y puertos locales

| Servicio | Puerto | Responsabilidad |
|---|---|---|
| Frontend | 4200 | Angular con MSAL |
| BFF | 8080 | Entrada del backend, revalida el JWT |
| Orders | 8081 | Pedidos, estados y outbox de comandos RabbitMQ y eventos Kafka |
| Catalog | 8082 | Productos, stock y tickets de cocina (consume `q.cmd.kitchen`) |
| Notify | 8083 | Correos (consume `q.cmd.email`) |
| Report | 8084 | Reportería y boletas (consume `q.cmd.invoice`) |
| Audit | 8085 | Historial y auditoría de dead letters (consume `q.audit.dead-letters`) |
| mq-admin | 8086 | Administrador de RabbitMQ: colas, exchanges, bindings, DLQ y replay (rol ADMIN) |
| RabbitMQ | 5672 / 15672 | Broker AMQP / consola de management |

## RabbitMQ (EP3)

Orders publica comandos asíncronos (correo, ticket de cocina, boleta) en los exchanges `cmd.direct` y `cmd.topic`; Notify, Catalog y Report los consumen con ACK manual, reintentos acotados y DLQ (`cmd.dead.dlx`); Audit registra cada dead letter y mq-admin permite crear y eliminar colas, exchanges y bindings, vigilar las DLQ y reprocesarlas. Todo está documentado en [docs/RABBITMQ.md](docs/RABBITMQ.md), incluida la tabla de la rúbrica.

```powershell
# Solo RabbitMQ + mq-admin
docker compose -f compose.rabbitmq.yml up -d --build
# Solución completa del backend (el orden de los -f importa)
docker compose -f compose.postgres.yml -f compose.catalog.yml -f compose.orders.yml -f compose.rabbitmq.yml -f compose.notify.yml -f compose.kafka.yml -f compose.audit.yml -f compose.report.yml -f compose.commands.yml up -d --build
```

## Reglas principales

- No desarrollar directamente en `main`.
- No subir credenciales ni tokens.
- Utilizar variables de entorno.
- Mantener los contratos acordados.
- Realizar Pull Requests pequeños y revisables.
- Comprobar que cada proyecto compile antes de fusionarlo.
- Mantener `main` estable.
