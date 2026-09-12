package cl.duoc.pedidos360.catalog.service;

import java.util.List;

import cl.duoc.pedidos360.catalog.dto.*;
import cl.duoc.pedidos360.catalog.entity.Product;
import cl.duoc.pedidos360.catalog.exception.ProductNotFoundException;
import cl.duoc.pedidos360.catalog.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CatalogService {
    private final ProductRepository repository;

    public CatalogService(ProductRepository repository) {
        this.repository = repository;
    }

    public List<ProductResponse> listProducts() {
        return repository.findByActiveTrueOrderByIdAsc().stream().map(this::response).toList();
    }

    public ProductResponse getProduct(long id) {
        return response(activeProduct(id));
    }

    @Transactional
    public ProductResponse createProduct(CreateProductRequest request) {
        return response(repository.saveAndFlush(new Product(request.name(), request.price(), request.stock())));
    }

    @Transactional
    public ProductResponse updateProduct(long id, UpdateProductRequest request) {
        Product product = activeProduct(id);
        product.updateDetails(request.name(), request.price());
        repository.flush();
        return response(product);
    }

    @Transactional
    public ProductResponse updateStock(long id, UpdateProductStockRequest request) {
        Product product = activeProduct(id);
        // Es el stock final definido por ADMIN, no un descuento de pedido.
        product.updateStock(request.stock());
        repository.flush();
        return response(product);
    }

    @Transactional
    public void deactivateProduct(long id) {
        Product product = repository.findById(id).orElseThrow(ProductNotFoundException::new);
        product.deactivate();
        repository.flush();
    }

    private Product activeProduct(long id) {
        return repository.findByIdAndActiveTrue(id).orElseThrow(ProductNotFoundException::new);
    }

    private ProductResponse response(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getPrice(),
                product.getStock(), product.isActive());
    }
}
