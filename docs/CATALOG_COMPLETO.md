# Catalog: contrato y prueba local

Catalog administra productos y existencias. El frontend consume las rutas públicas a través del BFF; no se conecta a PostgreSQL ni realiza descuentos de pedidos por su cuenta.

## API pública

Las rutas son iguales en BFF (`localhost:8080`) y Catalog (`localhost:8082`). Todas requieren un Access Token válido con scope `pedidos360.access`. Las escrituras requieren además el rol `ADMIN`.

| Método y ruta | Resultado | Permiso adicional |
|---|---|---|
| `GET /api/v1/catalog` | `200`, arreglo de productos activos ordenados por ID | Ninguno |
| `GET /api/v1/catalog/{id}` | `200`, producto activo | Ninguno |
| `POST /api/v1/catalog` | `201`, producto creado | ADMIN |
| `PUT /api/v1/catalog/{id}` | `200`, producto actualizado | ADMIN |
| `PATCH /api/v1/catalog/{id}/stock` | `200`, stock actualizado | ADMIN |
| `DELETE /api/v1/catalog/{id}` | `204`, sin cuerpo | ADMIN |

Creación:

```json
{"name":"Café de prueba","price":2500.50,"stock":10}
```

Edición: `{"name":"Café actualizado","price":3000}`. No modifica stock.

Ajuste de stock: `{"stock":7}`. Es el valor final, no una cantidad para sumar o restar.

Respuesta de producto:

```json
{"id":1,"name":"Café actualizado","price":3000.00,"stock":7,"active":true}
```

Reglas principales:

- Nombre no vacío, máximo 100 caracteres; precio mínimo `0.01`, hasta diez dígitos enteros y dos decimales; stock entero no negativo.
- Los IDs deben ser positivos. Campos desconocidos, valores fuera de rango y JSON inválido reciben `400`.
- Listar devuelve un arreglo, no una página. Incluye productos activos con stock cero.
- Desactivar no borra la fila. Repetir DELETE de un producto ya desactivado devuelve `204`; un ID inexistente devuelve `404`.
- GET, PUT y PATCH no operan sobre productos desactivados: devuelven `404`.
- La versión interna permite detectar actualizaciones concurrentes y no se expone en el contrato público.

## Seguridad y errores

BFF reenvía el mismo Bearer token a Catalog. Ambos verifican firma, issuer, audience y expiración; deben configurar los mismos `JWT_ISSUER_URI` y `JWT_AUDIENCE`. Un ID Token de inicio de sesión no reemplaza al Access Token de la API.

`401` indica token ausente o inválido. `403` indica falta de permisos. `409` indica un conflicto de stock o actualización concurrente. No se deben reintentar errores `400`, `401` o `403` sin corregir su causa.

Los errores tienen `timestamp`, `status`, `error`, `message`, `path` y `traceId`. `X-Trace-Id` se conserva entre BFF y Catalog cuando contiene 1–100 caracteres alfanuméricos, punto, guion o guion bajo. Si falta o es inválido, se genera uno nuevo. No se usa para identificar al usuario ni para autorizar.

## Stock interno para Orders

Estas rutas existen solamente en Catalog. No están expuestas por el BFF y no deben agregarse a API Gateway ni llamarse desde Angular. En esta versión requieren scope `pedidos360.access` y rol `ADMIN` u `OPERADOR`.

### Descontar al aceptar un pedido

`POST /internal/v1/catalog/stock/deductions`

```json
{
  "orderId": 7001,
  "items": [
    {"productId": 1, "quantity": 2},
    {"productId": 2, "quantity": 1}
  ]
}
```

Devuelve `204`. Acepta entre 1 y 50 productos diferentes, con IDs y cantidades positivos. IDs repetidos en la lista reciben `400`.

El descuento completo es una sola transacción: si falta un producto activo o no alcanza el stock, ningún producto queda descontado. Las filas se bloquean por ID para serializar operaciones concurrentes.

`orderId` es la clave de idempotencia. Repetir el mismo pedido con los mismos productos y cantidades no descuenta de nuevo. Cambiar el contenido o intentar descontar un pedido ya liberado devuelve `409`. Ante dos solicitudes simultáneas del mismo pedido, una puede recibir `409`; reintentar el mismo contenido no debe provocar otro descuento.

### Devolver al cancelar

`POST /internal/v1/catalog/stock/deductions/{orderId}/release`

Sin cuerpo; devuelve `204`. Devuelve exactamente las unidades registradas. Repetir la devolución no suma otra vez. Un pedido sin descuento registrado recibe `409`. Se permite devolver unidades de un producto desactivado, sin reactivarlo.

### Trabajo pendiente en Orders

Orders debe validar el estado y la pertenencia del pedido, conservar sus productos y cantidades, coordinar aceptación/cancelación y manejar reintentos. Catalog no puede comprobar si existe un pedido real porque no consulta la base de Orders. Un `204` de stock no cambia por sí solo el estado del pedido.

Las cancelaciones iniciadas por `CLIENTE` necesitarán un flujo interno autorizado: reenviar sin más su token a esta ruta devuelve `403`. Debemos definir esa comunicación al implementar Orders, sin inventar roles en el BFF ni permitir que un cliente descuente o libere stock directamente. No hay todavía transacciones distribuidas entre servicios ni una saga implementada.

## Ejecutar todo en Docker

Primero definir las contraseñas y los valores reales de Entra siguiendo [PostgreSQL local](POSTGRESQL_LOCAL.md). No iniciar además los mismos servicios con Maven en los mismos puertos.

En la raíz del repositorio y en la terminal que contiene las variables:

```powershell
Set-Location -LiteralPath 'D:\Cloud_Native_EVA'
docker compose -f compose.postgres.yml -f compose.catalog.yml up -d --build
docker compose -f compose.postgres.yml -f compose.catalog.yml ps
```

Comprobar hasta obtener `UP`:

```powershell
Invoke-RestMethod http://localhost:8082/actuator/health/readiness
Invoke-RestMethod http://localhost:8080/actuator/health
```

Compose espera que PostgreSQL esté saludable antes de iniciar Catalog. El BFF espera que el contenedor Catalog esté iniciado, no que haya terminado su arranque; por eso comprobamos salud antes del CRUD.

Este Compose solo incluye PostgreSQL, Catalog y BFF. Orders, Report, Audit, Notify y los brokers todavía no están incluidos: no esperar que sus rutas funcionen. Los puertos se publican únicamente en `127.0.0.1`. No es un despliegue AWS ni una configuración lista para producción.

Para revisar problemas sin imprimir variables de entorno:

```powershell
docker compose -f compose.postgres.yml -f compose.catalog.yml logs --tail 100 catalog bff
```

Para detener el conjunto y conservar los datos:

```powershell
docker compose -f compose.postgres.yml -f compose.catalog.yml stop
```

No utilizar `down -v` sobre la base de desarrollo. Las contraseñas definidas al crear el volumen deben conservarse; cambiar variables no cambia las credenciales guardadas en PostgreSQL.

## Postman y OpenAPI

Importar [Pedidos360 Catalog](../postman/Pedidos360-Catalog.postman_collection.json). Definir `accessToken` solo en un valor local o un entorno privado; nunca exportarlo con credenciales ni subirlo a GitHub. Debe ser un Access Token real para la API, con scope y rol adecuados.

Ejecutar las carpetas en orden. La petición de creación guarda `productId` y asigna un `orderId` temporal usando la hora actual; no representa un pedido creado en Orders. La carpeta de stock interno usa directamente `catalogUrl` y se ejecuta antes de desactivar el producto. No reutilizar ese `orderId` con otro contenido. Las peticiones internas son pruebas técnicas, no una implementación del flujo de Orders.

Con `API_DOCS_ENABLED=true` antes de iniciar los servicios, la UI está en `/swagger-ui/index.html` y el documento en `/v3/api-docs`. Esta bandera publica la documentación sin token; no habilita acceso anónimo a las operaciones. Mantenerla desactivada donde no corresponda.

## Comprobaciones realizadas

- BFF: 120 pruebas Maven exitosas.
- Catalog: 62 pruebas rápidas y 44 con PostgreSQL real, todas exitosas.
- Integración por HTTP con ambos JAR: 26 comprobaciones exitosas.
- Imágenes Docker construidas y arranque de los tres servicios verificado con Compose; BFF y Catalog respondieron `UP` usando Java 21 y el usuario `10001` (sin root).

El script `node scripts/test-bff-catalog.mjs` permite repetir la integración sin depender de cuentas Entra ni tocar datos de desarrollo. Las pruebas reales con frontend, Entra y AWS siguen pendientes.
