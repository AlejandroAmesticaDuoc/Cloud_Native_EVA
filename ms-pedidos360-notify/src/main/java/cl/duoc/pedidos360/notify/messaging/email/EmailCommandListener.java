package cl.duoc.pedidos360.notify.messaging.email;

import cl.duoc.pedidos360.notify.dto.EmailCommand;
import cl.duoc.pedidos360.notify.messaging.support.JsonCommandReader;
import cl.duoc.pedidos360.notify.messaging.support.ManualAckHandler;
import cl.duoc.pedidos360.notify.messaging.support.ProcessedMessageRegistry;
import cl.duoc.pedidos360.notify.service.EmailService;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumidor del dominio correo: q.cmd.email (email.send y email.send.high) --> EmailService.
 * ACK manual según la tabla de ManualAckHandler: inválido -> DLQ inmediato; SMTP caído -> reintentos y luego DLQ;
 * eventId repetido -> ack sin reenviar el correo.
 */
@Component
public class EmailCommandListener {
    private final JsonCommandReader reader;
    private final ManualAckHandler acks;
    private final ProcessedMessageRegistry processed;
    private final EmailService email;

    public EmailCommandListener(JsonCommandReader reader, ManualAckHandler acks, ProcessedMessageRegistry processed,
            EmailService email) {
        this.reader = reader;
        this.acks = acks;
        this.processed = processed;
        this.email = email;
    }

    @RabbitListener(id = "notify-email", queues = "${messaging.routes.email.queue}",
            containerFactory = "manualAckContainerFactory")
    public void onMessage(Message message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        acks.handle(message, channel, deliveryTag, this::process);
    }

    private ManualAckHandler.Outcome process(Message message) {
        EmailCommand command = reader.read(message, EmailCommand.class, EmailCommand::eventId);
        if (processed.isProcessed(command.eventId())) return ManualAckHandler.Outcome.DUPLICATE;
        email.send(command);
        processed.markProcessed(command.eventId());
        return ManualAckHandler.Outcome.PROCESSED;
    }
}
