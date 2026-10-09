package cl.duoc.pedidos360.orders.messaging;

/**
 * Tipo de comando guardado en la columna {@code command_type} de la outbox.
 * RabbitCommandPublisher traduce cada tipo a su exchange y routing key leídos de MessagingProperties.
 */
public enum CommandType {
    /** Correo de creación o cambio de estado: cmd.direct / email.send. */
    EMAIL,
    /** Correo prioritario por cancelación: cmd.topic / email.send.high. */
    EMAIL_PRIORITY,
    /** Ticket de cocina al aceptar: cmd.direct / kitchen.ticket. */
    KITCHEN_TICKET,
    /** Boleta al entregar: cmd.direct / invoice.gen. */
    INVOICE
}
