package cl.duoc.pedidos360.mqadmin.exception;

/** Se intentó eliminar una cola o exchange de la topología base de Pedidos360 (409). */
public class ProtectedResourceException extends ResourceConflictException {
    public ProtectedResourceException(String kind, String name) {
        super("%s '%s' es parte de la topología base de Pedidos360 y no se puede eliminar desde mq-admin"
                .formatted(kind, name));
    }
}
