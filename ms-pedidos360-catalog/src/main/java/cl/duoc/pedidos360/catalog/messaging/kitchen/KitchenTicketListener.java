package cl.duoc.pedidos360.catalog.messaging.kitchen;

import cl.duoc.pedidos360.catalog.dto.KitchenTicketCommand;
import cl.duoc.pedidos360.catalog.messaging.support.JsonCommandReader;
import cl.duoc.pedidos360.catalog.messaging.support.ManualAckHandler;
import cl.duoc.pedidos360.catalog.service.KitchenTicketService;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumidor del dominio cocina: q.cmd.kitchen (kitchen.ticket) --> KitchenTicketService.
 * ACK manual según ManualAckHandler: inválido -> DLQ inmediato; error de base -> reintentos y luego DLQ;
 * ticket ya registrado (eventId u orderId) -> ack sin repetir efectos.
 */
@Component
@ConditionalOnProperty(name = "catalog.commands.enabled", havingValue = "true")
public class KitchenTicketListener {
    private final JsonCommandReader reader;
    private final ManualAckHandler acks;
    private final KitchenTicketService tickets;

    public KitchenTicketListener(JsonCommandReader reader, ManualAckHandler acks, KitchenTicketService tickets) {
        this.reader = reader;
        this.acks = acks;
        this.tickets = tickets;
    }

    @RabbitListener(id = "catalog-kitchen-tickets", queues = "${messaging.routes.kitchen.queue}",
            containerFactory = "manualAckContainerFactory")
    public void onMessage(Message message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        acks.handle(message, channel, deliveryTag, this::process);
    }

    private ManualAckHandler.Outcome process(Message message) {
        var command = reader.read(message, KitchenTicketCommand.class, KitchenTicketCommand::eventId);
        return tickets.register(command) == KitchenTicketService.Result.DUPLICATE
                ? ManualAckHandler.Outcome.DUPLICATE : ManualAckHandler.Outcome.PROCESSED;
    }
}
