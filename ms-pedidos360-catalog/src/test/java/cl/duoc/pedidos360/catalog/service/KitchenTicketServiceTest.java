package cl.duoc.pedidos360.catalog.service;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import cl.duoc.pedidos360.catalog.dto.KitchenTicketCommand;
import cl.duoc.pedidos360.catalog.entity.Product;
import cl.duoc.pedidos360.catalog.exception.KitchenTicketNotFoundException;
import cl.duoc.pedidos360.catalog.repository.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class KitchenTicketServiceTest {
    @Autowired KitchenTicketService service;
    @Autowired ProductRepository products;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM kitchen_tickets");
        jdbc.update("DELETE FROM products");
    }

    KitchenTicketCommand command(UUID eventId, long orderId, List<KitchenTicketCommand.Item> items) {
        return new KitchenTicketCommand(1, eventId, orderId, "cliente-demo", items,
                Instant.parse("2026-10-08T15:30:00Z"), "trace-demo-001");
    }

    @Test
    void createsTheTicketWithCatalogNamesAndAFallbackForMissingProducts() {
        long coffee = products.saveAndFlush(new Product("Café grande", new BigDecimal("1500.00"), 10)).getId();
        long missing = coffee + 1000;
        var result = service.register(command(UUID.randomUUID(), 42, List.of(
                new KitchenTicketCommand.Item(coffee, 2), new KitchenTicketCommand.Item(missing, 1))));
        assertEquals(KitchenTicketService.Result.CREATED, result);
        var ticket = service.find(42);
        assertEquals(42L, ticket.orderId());
        assertEquals("cliente-demo", ticket.customerId());
        assertEquals(2, ticket.itemCount());
        assertEquals("trace-demo-001", ticket.traceId());
        assertTrue(ticket.ticket().contains("2 x Café grande (#" + coffee + ")"), ticket.ticket());
        assertTrue(ticket.ticket().contains("1 x Producto #" + missing), ticket.ticket());
        assertTrue(ticket.ticket().startsWith("TICKET DE COCINA - Pedido #42"));
        assertEquals(10, products.findById(coffee).orElseThrow().getStock(), "el ticket no modifica stock");
    }

    @Test
    void isIdempotentByEventIdAndByOrderId() {
        var eventId = UUID.randomUUID();
        var items = List.of(new KitchenTicketCommand.Item(7L, 1));
        assertEquals(KitchenTicketService.Result.CREATED, service.register(command(eventId, 42, items)));
        assertEquals(KitchenTicketService.Result.DUPLICATE, service.register(command(eventId, 42, items)));
        assertEquals(KitchenTicketService.Result.DUPLICATE, service.register(command(UUID.randomUUID(), 42, items)));
        assertEquals(KitchenTicketService.Result.DUPLICATE, service.register(command(eventId, 43, items)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM kitchen_tickets", Integer.class));
    }

    @Test
    void reportsMissingTickets() {
        assertThrows(KitchenTicketNotFoundException.class, () -> service.find(999));
    }

    @Test
    void createsNoRabbitBeansWhileCommandsAreDisabled() {
        assertTrue(context.getBeansOfType(Queue.class).isEmpty());
        assertTrue(context.getBeanNamesForType(
                cl.duoc.pedidos360.catalog.messaging.kitchen.KitchenTicketListener.class).length == 0);
        assertFalse(context.containsBean("manualAckContainerFactory"));
    }
}
