package cl.duoc.pedidos360.orders.repository;

import java.util.List;
import java.util.Optional;
import cl.duoc.pedidos360.orders.entity.PurchaseOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface OrderRepository extends JpaRepository<PurchaseOrder, Long> {
    List<PurchaseOrder> findAllByOrderByIdAsc();
    List<PurchaseOrder> findByCustomerIdOrderByIdAsc(String customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrder o where o.id = :id")
    Optional<PurchaseOrder> findLocked(long id);
}
