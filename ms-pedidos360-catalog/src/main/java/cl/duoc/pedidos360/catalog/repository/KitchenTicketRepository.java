package cl.duoc.pedidos360.catalog.repository;

import java.util.Optional;
import java.util.UUID;

import cl.duoc.pedidos360.catalog.entity.KitchenTicket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KitchenTicketRepository extends JpaRepository<KitchenTicket, Long> {

    boolean existsByEventIdOrOrderId(UUID eventId, Long orderId);

    Optional<KitchenTicket> findByOrderId(Long orderId);
}
