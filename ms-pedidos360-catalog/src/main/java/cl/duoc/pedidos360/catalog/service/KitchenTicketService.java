package cl.duoc.pedidos360.catalog.service;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import cl.duoc.pedidos360.catalog.dto.KitchenTicketCommand;
import cl.duoc.pedidos360.catalog.dto.KitchenTicketResponse;
import cl.duoc.pedidos360.catalog.entity.KitchenTicket;
import cl.duoc.pedidos360.catalog.entity.Product;
import cl.duoc.pedidos360.catalog.exception.KitchenTicketNotFoundException;
import cl.duoc.pedidos360.catalog.repository.KitchenTicketRepository;
import cl.duoc.pedidos360.catalog.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Genera el ticket de cocina de un pedido aceptado con los nombres de producto del catálogo.
 * No conoce RabbitMQ: recibe el comando ya validado. Es idempotente por eventId y por orderId.
 */
@Service
public class KitchenTicketService {
    private static final Logger LOG = LoggerFactory.getLogger(KitchenTicketService.class);
    private final KitchenTicketRepository tickets;
    private final ProductRepository products;

    public enum Result { CREATED, DUPLICATE }

    public KitchenTicketService(KitchenTicketRepository tickets, ProductRepository products) {
        this.tickets = tickets;
        this.products = products;
    }

    @Transactional
    public Result register(KitchenTicketCommand command) {
        if (tickets.existsByEventIdOrOrderId(command.eventId(), command.orderId())) {
            LOG.info("Ticket de cocina ya registrado; se omite orderId={} eventId={}", command.orderId(), command.eventId());
            return Result.DUPLICATE;
        }
        var ids = command.items().stream().map(KitchenTicketCommand.Item::productId).collect(Collectors.toSet());
        Map<Long, String> names = products.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Product::getName, (first, second) -> first));
        String text = render(command, id -> names.getOrDefault(id, "Producto #" + id));
        tickets.saveAndFlush(new KitchenTicket(command.eventId(), command.orderId(), command.customerId(),
                command.traceId(), command.items().size(), text, Instant.now()));
        LOG.info("Ticket de cocina generado orderId={} lineas={} traceId={}", command.orderId(),
                command.items().size(), command.traceId());
        return Result.CREATED;
    }

    @Transactional(readOnly = true)
    public KitchenTicketResponse find(long orderId) {
        return tickets.findByOrderId(orderId).map(ticket -> new KitchenTicketResponse(ticket.getOrderId(),
                ticket.getCustomerId(), ticket.getItemCount(), ticket.getTicketText(), ticket.getTraceId(),
                ticket.getCreatedAt())).orElseThrow(KitchenTicketNotFoundException::new);
    }

    private String render(KitchenTicketCommand command, Function<Long, String> name) {
        var text = new StringBuilder("TICKET DE COCINA - Pedido #").append(command.orderId()).append('\n')
                .append("Aceptado (UTC): ").append(command.occurredAt()).append('\n');
        for (var item : command.items()) {
            text.append("  ").append(item.quantity()).append(" x ").append(name.apply(item.productId()))
                    .append(" (#").append(item.productId()).append(")\n");
        }
        return text.append("Lineas: ").append(command.items().size())
                .append(" | Seguimiento: ").append(command.traceId()).toString();
    }
}
