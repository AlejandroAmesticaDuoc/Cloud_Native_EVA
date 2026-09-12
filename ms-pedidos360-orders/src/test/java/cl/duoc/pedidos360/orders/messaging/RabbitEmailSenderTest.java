package cl.duoc.pedidos360.orders.messaging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitEmailSenderTest {
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    RabbitEmailSender sender = new RabbitEmailSender(rabbit, "q.cmd.email");

    @Test
    void waitsForConfirmationAndSendsPersistentJson() throws Exception {
        doAnswer(call -> {
            Message message = call.getArgument(2);
            assertEquals("application/json", message.getMessageProperties().getContentType());
            assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getDeliveryMode());
            assertEquals("event-1", message.getMessageProperties().getMessageId());
            assertFalse(message.getMessageProperties().getHeaders().containsKey("Authorization"));
            ((CorrelationData) call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(eq(""), eq("q.cmd.email"), any(Message.class), any(CorrelationData.class));
        sender.send("event-1", "{}");
        verify(rabbit).send(eq(""), eq("q.cmd.email"), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void refusesBrokerNegativeConfirmation() {
        doAnswer(call -> {
            ((CorrelationData) call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThrows(IllegalStateException.class, () -> sender.send("event-1", "{}"));
    }

    @Test
    void refusesUnroutableMessageEvenWhenExchangeAcknowledgesIt() {
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.setReturned(new ReturnedMessage(call.getArgument(2), 312, "NO_ROUTE", "", "q.cmd.email"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThrows(IllegalStateException.class, () -> sender.send("event-1", "{}"));
    }

    @Test
    void propagatesConnectionFailure() {
        doAnswer(call -> {
            ((CorrelationData) call.getArgument(3)).getFuture().completeExceptionally(new IllegalStateException("closed"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThrows(ExecutionException.class, () -> sender.send("event-1", "{}"));
    }
}
