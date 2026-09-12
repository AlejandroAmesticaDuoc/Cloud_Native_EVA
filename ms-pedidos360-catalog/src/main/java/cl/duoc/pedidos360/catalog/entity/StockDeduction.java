package cl.duoc.pedidos360.catalog.entity;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import jakarta.persistence.*;

@Entity
@Table(name = "stock_deductions")
public class StockDeduction {
    @Id
    private Long orderId;

    @ElementCollection
    @CollectionTable(name = "stock_deduction_items", joinColumns = @JoinColumn(name = "order_id"))
    @MapKeyColumn(name = "product_id")
    @Column(name = "quantity", nullable = false)
    private Map<Long, Integer> items = new TreeMap<>();

    @Column(nullable = false)
    private boolean released;

    @Version
    @Column(nullable = false)
    private Long version;

    protected StockDeduction() {
    }

    public StockDeduction(Long orderId, Map<Long, Integer> items) {
        this.orderId = orderId;
        this.items.putAll(items);
    }

    public Map<Long, Integer> getItems() {
        return Collections.unmodifiableMap(items);
    }

    public boolean isReleased() {
        return released;
    }

    public void release() {
        released = true;
    }
}
