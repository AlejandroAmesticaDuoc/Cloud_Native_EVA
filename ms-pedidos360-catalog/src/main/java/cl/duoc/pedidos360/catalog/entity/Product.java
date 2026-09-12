package cl.duoc.pedidos360.catalog.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String name;

    @NotNull
    @DecimalMin("0.01")
    @Digits(integer = 10, fraction = 2)
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @NotNull
    @PositiveOrZero
    @Column(nullable = false)
    private Integer stock;

    @Column(nullable = false)
    private boolean active = true;

    // Impide sobrescribir silenciosamente una modificación concurrente.
    @Version
    @Column(nullable = false)
    private long version;

    protected Product() {
        // Constructor requerido por JPA.
    }

    public Product(String name, BigDecimal price, Integer stock) {
        this.name = name;
        this.price = price;
        this.stock = stock;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Integer getStock() {
        return stock;
    }

    public boolean isActive() {
        return active;
    }

    public long getVersion() {
        return version;
    }

    public void updateDetails(String name, BigDecimal price) {
        this.name = name;
        this.price = price;
    }

    public void updateStock(Integer stock) {
        this.stock = stock;
    }

    public void deactivate() {
        this.active = false;
    }
}
