package cl.duoc.pedidos360.orders.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import cl.duoc.pedidos360.orders.dto.OrderStatus;
import jakarta.persistence.*;

@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 200)
    private String customerId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status;
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private OrderStatus pendingStatus;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false, precision = 24, scale = 2)
    private BigDecimal total;
    @ElementCollection
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_number")
    private List<OrderItem> items = new ArrayList<>();
    @Version
    @Column(nullable = false)
    private long version;

    protected PurchaseOrder() {}

    public PurchaseOrder(String customerId, List<OrderItem> items) {
        this.customerId = customerId;
        this.items.addAll(items);
        this.status = OrderStatus.CREADO;
        this.createdAt = Instant.now();
        this.total = items.stream().map(item -> item.getUnitPrice()
                .multiply(BigDecimal.valueOf(item.getQuantity()))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Long getId() { return id; }
    public String getCustomerId() { return customerId; }
    public OrderStatus getStatus() { return status; }
    public OrderStatus getPendingStatus() { return pendingStatus; }
    public Instant getCreatedAt() { return createdAt; }
    public BigDecimal getTotal() { return total; }
    public List<OrderItem> getItems() { return Collections.unmodifiableList(items); }
    public void prepare(OrderStatus target) { pendingStatus = target; }
    public void clearPending() { pendingStatus = null; }
    public void complete(OrderStatus target) {
        status = target;
        pendingStatus = null;
    }
}
