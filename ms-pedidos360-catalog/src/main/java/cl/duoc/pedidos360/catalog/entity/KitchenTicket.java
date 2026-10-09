package cl.duoc.pedidos360.catalog.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

/** Ticket de cocina generado a partir del comando {@code kitchen.ticket}; uno por pedido. */
@Entity
@Table(name = "kitchen_tickets")
public class KitchenTicket {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID eventId;

    @Column(nullable = false, unique = true)
    private Long orderId;

    @Column(nullable = false, length = 200)
    private String customerId;

    @Column(nullable = false, length = 100)
    private String traceId;

    @Column(nullable = false)
    private Integer itemCount;

    @Column(nullable = false, length = 8000)
    private String ticketText;

    @Column(nullable = false)
    private Instant createdAt;

    protected KitchenTicket() {
        // Constructor requerido por JPA.
    }

    public KitchenTicket(UUID eventId, Long orderId, String customerId, String traceId, Integer itemCount,
            String ticketText, Instant createdAt) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.customerId = customerId;
        this.traceId = traceId;
        this.itemCount = itemCount;
        this.ticketText = ticketText;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public UUID getEventId() { return eventId; }
    public Long getOrderId() { return orderId; }
    public String getCustomerId() { return customerId; }
    public String getTraceId() { return traceId; }
    public Integer getItemCount() { return itemCount; }
    public String getTicketText() { return ticketText; }
    public Instant getCreatedAt() { return createdAt; }
}
