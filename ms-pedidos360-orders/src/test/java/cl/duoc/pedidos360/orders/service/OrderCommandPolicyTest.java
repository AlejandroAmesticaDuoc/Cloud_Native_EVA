package cl.duoc.pedidos360.orders.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Arrays;
import java.util.List;
import cl.duoc.pedidos360.orders.dto.OrderStatus;
import cl.duoc.pedidos360.orders.messaging.CommandType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderCommandPolicyTest {
    @Test
    void creationSendsOnlyTheRegularEmail() {
        assertEquals(List.of(CommandType.EMAIL), OrderCommandPolicy.commandsFor(null, OrderStatus.CREADO));
    }

    @ParameterizedTest
    @CsvSource({
        "CREADO,ACEPTADO,EMAIL KITCHEN_TICKET",
        "ACEPTADO,EN_PREPARACION,EMAIL",
        "EN_PREPARACION,DESPACHADO,EMAIL",
        "DESPACHADO,ENTREGADO,EMAIL INVOICE",
        "CREADO,CANCELADO,EMAIL_PRIORITY",
        "ACEPTADO,CANCELADO,EMAIL_PRIORITY"})
    void appliesTheContractRuleForEachTransition(OrderStatus previous, OrderStatus current, String expected) {
        var commands = Arrays.stream(expected.split(" ")).map(CommandType::valueOf).toList();
        assertEquals(commands, OrderCommandPolicy.commandsFor(previous, current));
    }
}
