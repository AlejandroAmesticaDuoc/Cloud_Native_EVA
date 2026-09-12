package cl.duoc.pedidos360.notify.service;

import cl.duoc.pedidos360.notify.config.EmailSettings;
import cl.duoc.pedidos360.notify.dto.EmailCommand;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {
    private final JavaMailSender sender;
    private final EmailSettings settings;

    public EmailService(JavaMailSender sender, EmailSettings settings) {
        this.sender = sender;
        this.settings = settings;
    }

    public void send(EmailCommand command) {
        var message = new SimpleMailMessage();
        message.setFrom(settings.from());
        message.setTo(settings.recipient());
        message.setSubject("Pedidos360 | Pedido #" + command.orderId() + " | " + command.status());
        message.setText("""
                El pedido #%d se encuentra en estado %s.

                Fecha del evento (UTC): %s
                Identificador del aviso: %s
                Seguimiento: %s

                Aviso de demostración de Pedidos360.
                """.formatted(command.orderId(), command.status(), command.occurredAt(),
                        command.eventId(), command.traceId()));
        sender.send(message);
    }
}
