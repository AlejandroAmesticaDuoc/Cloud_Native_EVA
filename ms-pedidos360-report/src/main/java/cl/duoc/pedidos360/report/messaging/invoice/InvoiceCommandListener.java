package cl.duoc.pedidos360.report.messaging.invoice;

import cl.duoc.pedidos360.report.dto.InvoiceCommand;
import cl.duoc.pedidos360.report.exception.InvoiceRejectedException;
import cl.duoc.pedidos360.report.messaging.support.InvalidMessageException;
import cl.duoc.pedidos360.report.messaging.support.JsonCommandReader;
import cl.duoc.pedidos360.report.messaging.support.ManualAckHandler;
import cl.duoc.pedidos360.report.service.InvoiceService;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumidor del dominio facturación: q.cmd.invoice (invoice.gen) --> InvoiceService.
 * ACK manual según ManualAckHandler: inválido o ítems que no suman el total -> DLQ inmediato;
 * error de base -> reintentos y luego DLQ; boleta ya emitida -> ack sin repetir efectos.
 */
@Component
@ConditionalOnProperty(name = "report.commands.enabled", havingValue = "true")
public class InvoiceCommandListener {
    private final JsonCommandReader reader;
    private final ManualAckHandler acks;
    private final InvoiceService invoices;

    public InvoiceCommandListener(JsonCommandReader reader, ManualAckHandler acks, InvoiceService invoices) {
        this.reader = reader;
        this.acks = acks;
        this.invoices = invoices;
    }

    @RabbitListener(id = "report-invoices", queues = "${messaging.routes.invoice.queue}",
            containerFactory = "manualAckContainerFactory")
    public void onMessage(Message message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        acks.handle(message, channel, deliveryTag, this::process);
    }

    private ManualAckHandler.Outcome process(Message message) {
        var command = reader.read(message, InvoiceCommand.class, InvoiceCommand::eventId);
        try {
            return invoices.issue(command) == InvoiceService.Result.DUPLICATE
                    ? ManualAckHandler.Outcome.DUPLICATE : ManualAckHandler.Outcome.PROCESSED;
        } catch (InvoiceRejectedException rejected) {
            // Regla de negocio imposible: reintentar no cambia el resultado.
            throw new InvalidMessageException(rejected.getMessage(), rejected);
        }
    }
}
