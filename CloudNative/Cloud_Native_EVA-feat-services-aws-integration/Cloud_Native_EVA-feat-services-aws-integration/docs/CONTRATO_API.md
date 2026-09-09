# Contrato inicial de la API

## Objetivo

Este documento define las primeras rutas, roles, estados y formatos que utilizarán el frontend, el BFF y los microservicios.

El contrato debe ser respetado por los tres integrantes. Si necesitamos modificarlo, el cambio debe ser conversado antes de implementarlo.

## URL base

Todas las rutas públicas utilizarán:

```text
/api/v1
```

El frontend consumirá estas rutas mediante AWS API Gateway.

## Autenticación

Las peticiones protegidas deben incluir:

```http
Authorization: Bearer <access_token>
```

No se debe enviar el ID Token como credencial de la API.

## Roles

| Rol | Descripción |
|---|---|
| `ADMIN` | Administra catálogo, pedidos y reportería |
| `OPERADOR` | Gestiona pedidos y actualiza sus estados |
| `CLIENTE` | Crea pedidos y consulta sus propios pedidos |
| `AUDITOR` | Consulta la trazabilidad sin modificar información |

## Pedidos

| Método | Ruta | Roles | Descripción |
|---|---|---|---|
| `GET` | `/api/v1/orders` | `ADMIN`, `OPERADOR`, `CLIENTE` | Lista pedidos; el cliente solamente ve los propios |
| `GET` | `/api/v1/orders/{id}` | `ADMIN`, `OPERADOR`, `CLIENTE` | Consulta un pedido |
| `POST` | `/api/v1/orders` | `CLIENTE`, `OPERADOR` | Crea un pedido |
| `PATCH` | `/api/v1/orders/{id}/status` | `ADMIN`, `OPERADOR` | Cambia el estado |
| `POST` | `/api/v1/orders/{id}/cancel` | `ADMIN`, `OPERADOR`, `CLIENTE` | Cancela un pedido si la regla lo permite |

No se utilizará eliminación física de pedidos durante esta etapa. La cancelación se manejará como un estado.

## Catálogo

La lectura del catálogo estará disponible para usuarios autenticados, ya que un cliente necesita consultar los productos antes de crear un pedido.

| Método | Ruta | Roles | Descripción |
|---|---|---|---|
| `GET` | `/api/v1/catalog` | Todos los autenticados | Lista productos disponibles |
| `GET` | `/api/v1/catalog/{id}` | Todos los autenticados | Consulta un producto |
| `POST` | `/api/v1/catalog` | `ADMIN` | Crea un producto |
| `PUT` | `/api/v1/catalog/{id}` | `ADMIN` | Modifica un producto |
| `PATCH` | `/api/v1/catalog/{id}/stock` | `ADMIN` | Actualiza el stock |
| `DELETE` | `/api/v1/catalog/{id}` | `ADMIN` | Desactiva un producto |

La eliminación de un producto debería ser lógica para no perder el historial de pedidos.

## Reportería

| Método | Ruta | Roles | Descripción |
|---|---|---|---|
| `GET` | `/api/v1/reports/summary` | `ADMIN` | Obtiene un resumen de pedidos y ventas |
| `GET` | `/api/v1/reports/lead-time` | `ADMIN` | Consulta el tiempo promedio de entrega |

Estas rutas podrán comenzar con respuestas básicas y luego conectarse con Kafka.

## Auditoría

| Método | Ruta | Roles | Descripción |
|---|---|---|---|
| `GET` | `/api/v1/audit` | `ADMIN`, `AUDITOR` | Lista eventos de auditoría |
| `GET` | `/api/v1/audit/orders/{orderId}` | `ADMIN`, `AUDITOR` | Muestra la trazabilidad de un pedido |

Auditoría será de solo lectura.

## Estados del pedido

Los valores utilizados por la API serán:

```text
CREADO
ACEPTADO
EN_PREPARACION
DESPACHADO
ENTREGADO
CANCELADO
```

## Transiciones permitidas

```text
CREADO
  -> ACEPTADO
  -> EN_PREPARACION
  -> DESPACHADO
  -> ENTREGADO
```

La cancelación se evaluará inicialmente desde:

```text
CREADO -> CANCELADO
ACEPTADO -> CANCELADO
```

## Reglas de negocio iniciales

- No se puede despachar un pedido que no haya sido aceptado.
- No se puede entregar un pedido que no haya sido despachado.
- No se puede modificar un pedido entregado.
- No se puede modificar un pedido cancelado.
- Al aceptar un pedido se debe descontar el stock.
- No se puede aceptar un pedido sin stock suficiente.
- Un cliente solo puede consultar o cancelar sus propios pedidos.
- Un auditor no puede modificar información.

## Ejemplo de creación de pedido

```json
{
  "items": [
    {
      "productId": 10,
      "quantity": 2
    },
    {
      "productId": 15,
      "quantity": 1
    }
  ]
}
```

## Ejemplo de respuesta

```json
{
  "id": 1001,
  "customerId": "usuario-entra-id",
  "status": "CREADO",
  "createdAt": "2026-09-08T18:30:00Z",
  "items": [
    {
      "productId": 10,
      "quantity": 2,
      "unitPrice": 4500
    }
  ],
  "total": 9000
}
```

## Cambio de estado

```json
{
  "status": "ACEPTADO"
}
```

## Formato común de errores

```json
{
  "timestamp": "2026-09-08T18:35:00Z",
  "status": 403,
  "error": "Forbidden",
  "message": "El usuario no tiene permisos para realizar esta acción",
  "path": "/api/v1/orders/1001/status",
  "traceId": "identificador-de-traza"
}
```

## Códigos esperados

| Código | Uso |
|---:|---|
| `200` | Consulta o actualización exitosa |
| `201` | Recurso creado |
| `204` | Operación exitosa sin contenido |
| `400` | Datos o transición inválida |
| `401` | Token ausente o inválido |
| `403` | Usuario autenticado sin permisos |
| `404` | Recurso no encontrado |
| `409` | Conflicto de estado o stock |
| `500` | Error interno |

## Versionamiento

Durante esta etapa trabajaremos solamente con `v1`.

Se creará `v2` únicamente cuando exista un cambio incompatible con la versión anterior.
