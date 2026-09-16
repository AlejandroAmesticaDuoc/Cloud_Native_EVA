package cl.duoc.pedidos360.catalog.service;

import java.util.Map;
import java.util.TreeMap;

import cl.duoc.pedidos360.catalog.dto.StockDeductionRequest;
import cl.duoc.pedidos360.catalog.entity.Product;
import cl.duoc.pedidos360.catalog.entity.StockDeduction;
import cl.duoc.pedidos360.catalog.exception.ProductNotFoundException;
import cl.duoc.pedidos360.catalog.exception.StockConflictException;
import cl.duoc.pedidos360.catalog.exception.InsufficientStockException;
import cl.duoc.pedidos360.catalog.repository.ProductRepository;
import cl.duoc.pedidos360.catalog.repository.StockDeductionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockService {
    private final ProductRepository products;
    private final StockDeductionRepository deductions;

    public StockService(ProductRepository products, StockDeductionRepository deductions) {
        this.products = products;
        this.deductions = deductions;
    }

    @Transactional
    public void deduct(StockDeductionRequest request) {
        Map<Long, Integer> requestedItems = normalize(request);
        StockDeduction existing = deductions.findLocked(request.orderId()).orElse(null);
        if (existing != null) {
            if (existing.isReleased() || !existing.getItems().equals(requestedItems)) {
                throw new StockConflictException("El pedido ya tiene otro movimiento de stock registrado");
            }
            return;
        }

        Map<Long, Product> locked = new TreeMap<>();
        for (Long productId : requestedItems.keySet()) {
            locked.put(productId, products.findLocked(productId).orElseThrow(ProductNotFoundException::new));
        }
        existing = deductions.findById(request.orderId()).orElse(null);
        if (existing != null) {
            if (existing.isReleased() || !existing.getItems().equals(requestedItems)) {
                throw new StockConflictException("El pedido ya tiene otro movimiento de stock registrado");
            }
            return;
        }
        for (Map.Entry<Long, Integer> item : requestedItems.entrySet()) {
            Product product = locked.get(item.getKey());
            if (!product.isActive()) {
                throw new ProductNotFoundException();
            }
            if (product.getStock() < item.getValue()) {
                throw new InsufficientStockException();
            }
            product.updateStock(product.getStock() - item.getValue());
        }

        deductions.saveAndFlush(new StockDeduction(request.orderId(), requestedItems));
    }

    @Transactional
    public void release(long orderId) {
        StockDeduction deduction = deductions.findLocked(orderId).orElseThrow(() ->
                new StockConflictException("El pedido no tiene un descuento registrado"));
        if (deduction.isReleased()) {
            return;
        }
        for (Map.Entry<Long, Integer> item : new TreeMap<>(deduction.getItems()).entrySet()) {
            Product product = products.findLocked(item.getKey()).orElseThrow(ProductNotFoundException::new);
            long restored = (long) product.getStock() + item.getValue();
            if (restored > Integer.MAX_VALUE) {
                throw new StockConflictException("La devolución supera el stock máximo permitido");
            }
            product.updateStock((int) restored);
        }
        deduction.release();
        deductions.flush();
    }

    private Map<Long, Integer> normalize(StockDeductionRequest request) {
        Map<Long, Integer> items = new TreeMap<>();
        for (StockDeductionRequest.Item item : request.items()) {
            if (items.putIfAbsent(item.productId(), item.quantity()) != null) {
                throw new IllegalArgumentException("Un producto no debe aparecer dos veces en el pedido");
            }
        }
        return items;
    }
}
