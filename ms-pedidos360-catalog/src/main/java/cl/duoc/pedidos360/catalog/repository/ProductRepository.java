package cl.duoc.pedidos360.catalog.repository;

import java.util.List;
import java.util.Optional;

import cl.duoc.pedidos360.catalog.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByActiveTrueOrderByIdAsc();

    Optional<Product> findByIdAndActiveTrue(Long id);
}
