# Contrato interno entre BFF y microservicios

## Objetivo

Este documento describe las solicitudes que actualmente construye el BFF y las respuestas que espera recibir de Orders y Catalog.

La idea es que podamos desarrollar por separado y después integrar los componentes sin tener diferencias en rutas o formatos.

Complementa el [contrato público de la API](../../docs/CONTRATO_API.md). No reemplaza las reglas de negocio acordadas.

## Estado del acuerdo

El comportamiento del BFF está implementado y probado con respuestas simuladas.

El encargado de los microservicios debe revisar este documento y confirmar que sus endpoints sean compatibles. La integración real todavía está pendiente.

Si necesitamos cambiar rutas, campos o respuestas, debemos acordarlo antes de modificar el código.

## Direcciones locales

| Servicio | Variable utilizada por el BFF | Valor de referencia |
|---|---|---|
| Orders | `ORDERS_SERVICE_URL` | `http://localhost:8081` |
| Catalog | `CATALOG_SERVICE_URL` | `http://localhost:8082` |

Las variables contienen solamente la URL base.

Por ejemplo:

```text
ORDERS_SERVICE_URL=http://localhost:8081
```

El BFF agrega `/api/v1/orders` para construir la solicitud completa.

No configurar la variable como `http://localhost:8081/api/v1/orders`.

En Docker se deberán utilizar las direcciones internas acordadas con el encargado de infraestructura.

## Headers enviados por el BFF

```http
Authorization: Bearer <access_token>
Accept: application/json
X-Trace-Id: prueba-pedido-001
```

Cuando existe un cuerpo JSON, también se envía:

```http
Content-Type: application/json
```

El token reenviado es el del usuario autenticado. El BFF no genera un token nuevo ni utiliza un client secret para estas llamadas.

Si los microservicios también validan JWT, su configuración debe ser compatible con el token destinado a la API del equipo.

Para `X-Trace-Id`, el BFF conserva el valor recibido cuando contiene entre 1 y 100 caracteres de este conjunto:

```text
Letras, números, punto, guion y guion bajo.
```

Si falta o no cumple ese formato, genera un UUID para las llamadas internas de esa solicitud.

El BFF también incluye el header de traza en sus respuestas. En errores, el valor coincide con el `traceId` del cuerpo.

## Formato general

- Los cuerpos de solicitud y respuesta utilizan JSON.
- Los identificadores de pedidos y productos son números enteros positivos.
- `customerId` es una cadena, no un identificador numérico.
- Los precios y totales se envían como números JSON.
- Las fechas de pedidos usan un formato compatible con `Instant`, por ejemplo `2026-09-11T12:00:00Z`.
- Las consultas de listas devuelven un arreglo JSON directo.
- Si una lista está vacía, la respuesta es `[]`, no `null` ni un cuerpo vacío.

No utilizar un objeto contenedor como este en los endpoints actuales de listado:

```json
{
  "data": [],
  "total": 0
}
```

El BFF todavía no implementa ese formato ni paginación.

# Orders Service

## Rutas internas esperadas

| Método | Ruta | Respuesta esperada |
|---|---|---|
| `GET` | `/api/v1/orders` | `200` con un arreglo de pedidos. |
| `GET` | `/api/v1/orders?customerId=<id>` | `200` con los pedidos de ese cliente. |
| `GET` | `/api/v1/orders/{id}` | `200` con un pedido. |
| `POST` | `/api/v1/orders` | `201` con el pedido creado. |
| `PATCH` | `/api/v1/orders/{id}/status` | `200` con el pedido actualizado. |
| `POST` | `/api/v1/orders/{id}/cancel` | `204` sin cuerpo. |

## Identidad del cliente

El BFF obtiene el identificador desde el JWT:

1. Utiliza el claim `oid` cuando está presente y no está vacío.
2. En caso contrario, utiliza `sub`.

No utiliza el correo como identificador del dueño del pedido.

Para listar pedidos de un cliente, el BFF envía ese identificador mediante el parámetro `customerId`. Orders debe aplicar el filtro.

Para consultar o cancelar un pedido, el BFF también comprueba la propiedad cuando el usuario no es administrador ni operador.

La seguridad de los servicios y su aislamiento de red deben coordinarse con el encargado de infraestructura. No deben exponerse directamente al frontend.

## Crear un pedido

El frontend envía al BFF:

```json
{
  "items": [
    {
      "productId": 10,
      "quantity": 2
    }
  ]
}
```

El BFF agrega la identidad autenticada y envía a Orders:

```json
{
  "customerId": "identificador-obtenido-del-jwt",
  "items": [
    {
      "productId": 10,
      "quantity": 2
    }
  ]
}
```

Diferencias importantes:

- El frontend no debe enviar `customerId`.
- El BFF no recibe precios ni totales para crear el pedido.
- Orders debe calcular precios y total según sus reglas.
- Actualmente, si un operador crea un pedido, se utiliza el identificador del propio operador. Crear pedidos en nombre de otro cliente requeriría un acuerdo y un cambio adicional.

Validaciones del cuerpo público:

- `items` debe contener entre 1 y 50 elementos.
- `productId` es obligatorio y positivo.
- `quantity` es obligatoria y positiva.

## Respuesta de un pedido

Ejemplo del objeto esperado en consultas, creación y actualización de estado:

```json
{
  "id": 1001,
  "customerId": "identificador-obtenido-del-jwt",
  "status": "CREADO",
  "createdAt": "2026-09-11T12:00:00Z",
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

Los campos esperados son:

- Pedido: `id`, `customerId`, `status`, `createdAt`, `items` y `total`.
- Cada elemento: `productId`, `quantity` y `unitPrice`.

`customerId` debe conservar el identificador del dueño del pedido, porque el BFF lo utiliza para controlar el acceso.

## Cambiar el estado

Solicitud:

```http
PATCH /api/v1/orders/1001/status
```

Cuerpo:

```json
{
  "status": "ACEPTADO"
}
```

Valores reconocidos por el BFF:

```text
CREADO
ACEPTADO
EN_PREPARACION
DESPACHADO
ENTREGADO
CANCELADO
```

Los valores deben coincidir exactamente, incluyendo mayúsculas y `EN_PREPARACION` sin tilde.

El BFF valida que el estado sea reconocido, pero Orders debe decidir si la transición está permitida.

El descuento de stock, las restricciones por estado y la publicación de eventos corresponden a los microservicios.

## Cancelar un pedido

Solicitud:

```http
POST /api/v1/orders/1001/cancel
```

No se envía un cuerpo JSON.

La respuesta acordada es `204 No Content`.

La cancelación no representa una eliminación física del pedido.

# Catalog Service

## Rutas internas esperadas

| Método | Ruta | Respuesta esperada |
|---|---|---|
| `GET` | `/api/v1/catalog` | `200` con un arreglo de productos. |
| `GET` | `/api/v1/catalog/{id}` | `200` con un producto. |
| `POST` | `/api/v1/catalog` | `201` con el producto creado. |
| `PUT` | `/api/v1/catalog/{id}` | `200` con el producto actualizado. |
| `PATCH` | `/api/v1/catalog/{id}/stock` | `200` con el producto actualizado. |
| `DELETE` | `/api/v1/catalog/{id}` | `204` sin cuerpo. |

## Crear un producto

```json
{
  "name": "Teclado",
  "price": 19990,
  "stock": 5
}
```

Validaciones:

- `name`: obligatorio, no puede estar en blanco y admite hasta 100 caracteres.
- `price`: obligatorio, mínimo `0.01`, hasta 10 dígitos enteros y 2 decimales.
- `stock`: obligatorio, entero mayor o igual a cero.

## Modificar un producto

Solicitud:

```http
PUT /api/v1/catalog/10
```

Cuerpo:

```json
{
  "name": "Teclado mecánico",
  "price": 24990
}
```

Este endpoint recibe solamente `name` y `price`.

No enviar `id`, `stock` ni `active` en este cuerpo. El identificador está en la ruta y el stock tiene su propio endpoint.

El BFF está configurado para rechazar propiedades desconocidas en los cuerpos de solicitud.

## Actualizar el stock

Solicitud:

```http
PATCH /api/v1/catalog/10/stock
```

Cuerpo:

```json
{
  "stock": 20
}
```

`stock` es obligatorio y debe ser un entero mayor o igual a cero.

El BFF reenvía el valor recibido; no realiza operaciones aritméticas sobre el stock.

Catalog confirma que este endpoint establece el stock final. No debe confundirse con el descuento de unidades que Orders solicita al aceptar un pedido.

## Respuesta de un producto

```json
{
  "id": 10,
  "name": "Teclado",
  "price": 19990,
  "stock": 5,
  "active": true
}
```

Los campos esperados son `id`, `name`, `price`, `stock` y `active`.

El BFF no calcula estos valores: los recibe desde Catalog.

## Desactivar un producto

Solicitud:

```http
DELETE /api/v1/catalog/10
```

No se envía un cuerpo JSON.

La respuesta acordada es `204 No Content`.

Catalog debe aplicar la desactivación lógica acordada para conservar el historial. El BFF solamente solicita la operación.

# Manejo de errores

Los clientes HTTP del BFF interpretan principalmente el código HTTP recibido. No entregan directamente al frontend el cuerpo de error original del microservicio.

| Situación en el microservicio | Respuesta actual del BFF |
|---|---|
| `400 Bad Request` | `400` con mensaje controlado. |
| `404 Not Found` | `404` con mensaje controlado. |
| `409 Conflict` | `409` con mensaje controlado. |
| `403 Forbidden` de Orders | `403` con mensaje controlado, sin copiar el cuerpo interno. |
| Otros errores `4xx` o `5xx` | `502 Bad Gateway`. |
| Fallo de conexión o timeout | `502 Bad Gateway`. |
| Cuerpo ausente donde se espera un objeto o una lista | `502 Bad Gateway`. |
| JSON que el cliente HTTP no puede convertir al DTO esperado | `502 Bad Gateway`. |

Importante: un `401` de un microservicio se transforma en `502`; un `403` de Catalog también. Orders puede rechazar la propiedad de un pedido con `403`, y ese código se conserva.

Esto es distinto de los `401` y `403` generados por la propia seguridad del BFF. Si ocurre durante la integración, debemos revisar la configuración de seguridad del servicio; no asumir que se reenviará el mismo código al frontend.

Ejemplo de error público generado por el BFF:

```json
{
  "timestamp": "2026-09-11T12:05:00Z",
  "status": 502,
  "error": "Bad Gateway",
  "message": "El servicio de pedidos no está disponible",
  "path": "/api/v1/orders",
  "traceId": "prueba-pedido-001"
}
```

# Auditoría y reportería

Quedan pendientes las conexiones reales para:

- `GET /api/v1/reports/summary`
- `GET /api/v1/reports/lead-time`
- `GET /api/v1/audit`
- `GET /api/v1/audit/orders/{orderId}`

Actualmente solo existen sus reglas de autorización y controladores simulados dentro de las pruebas.

Antes de implementar los clientes HTTP debemos acordar los cuerpos de respuesta con el encargado de Report y Audit.

Las variables `REPORT_SERVICE_URL` y `AUDIT_SERVICE_URL` aparecen en `.env.example`, pero el BFF todavía no las utiliza.

# Checklist de integración

Antes de dar por integrada esta parte, comprobar:

- [ ] Orders y Catalog levantan con las URLs configuradas.
- [ ] Las rutas internas incluyen `/api/v1`.
- [ ] Las listas se entregan como arreglos JSON directos.
- [ ] Orders acepta el `customerId` agregado por el BFF.
- [ ] Orders filtra correctamente por `customerId`.
- [ ] Las respuestas mantienen los nombres y tipos de los campos acordados.
- [ ] Los estados del pedido coinciden con el enum del BFF.
- [ ] La actualización de stock tiene una semántica acordada.
- [ ] Las creaciones devuelven `201` con el recurso.
- [ ] La cancelación y la desactivación devuelven `204` sin cuerpo.
- [ ] Los servicios reciben Authorization y X-Trace-Id.
- [ ] Los permisos funcionan con tokens reales de Entra ID.
- [ ] Un cliente no puede consultar ni cancelar pedidos ajenos.
- [ ] Los errores reales se traducen como se indica en este documento.
- [ ] El equipo confirma que no se expusieron secretos ni servicios internos.
