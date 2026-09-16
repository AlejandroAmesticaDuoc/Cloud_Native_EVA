package cl.duoc.pedidos360.notify.messaging;

import cl.duoc.pedidos360.notify.dto.EmailCommand;
import cl.duoc.pedidos360.notify.service.EmailService;
import jakarta.validation.Validator;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class EmailCommandListener {
    private final JsonMapper json;
    private final Validator validator;
    private final EmailService email;

    public EmailCommandListener(JsonMapper json, Validator validator, EmailService email) {
        this.json = json;
        this.validator = validator;
        this.email = email;
    }

    @RabbitListener(queues = "${messaging.email.queue}")
    public void receive(Message message) {
        EmailCommand command;
        try {
            if (message.getBody().length > 8192
                    || !"application/json".equals(message.getMessageProperties().getContentType())) {
                throw new IllegalArgumentException();
            }
            command = json.readValue(message.getBody(), EmailCommand.class);
            if (command == null || !validator.validate(command).isEmpty()) throw new IllegalArgumentException();
        } catch (RuntimeException exception) {
            throw new AmqpRejectAndDontRequeueException("Comando de notificación inválido");
        }
        email.send(command);
    }
}
