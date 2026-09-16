package cl.duoc.pedidos360.catalog.repository;

import java.util.Optional;

import cl.duoc.pedidos360.catalog.entity.StockDeduction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockDeductionRepository extends JpaRepository<StockDeduction, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select deduction from StockDeduction deduction where deduction.orderId = :orderId")
    Optional<StockDeduction> findLocked(@Param("orderId") Long orderId);
}
