package cl.duoc.pedidos360.bff.service;

import java.util.List;

import cl.duoc.pedidos360.bff.client.CatalogClient;
import cl.duoc.pedidos360.bff.dto.catalog.CreateProductRequest;
import cl.duoc.pedidos360.bff.dto.catalog.ProductResponse;
import cl.duoc.pedidos360.bff.dto.catalog.UpdateProductRequest;
import cl.duoc.pedidos360.bff.dto.catalog.UpdateProductStockRequest;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {

    private final CatalogClient catalogClient;

    public CatalogService(CatalogClient catalogClient) {
        this.catalogClient = catalogClient;
    }

    public List<ProductResponse> listProducts() {
        return catalogClient.listProducts();
    }

    public ProductResponse getProduct(long id) {
        return catalogClient.getProduct(id);
    }

    public ProductResponse createProduct(
            CreateProductRequest request) {

        return catalogClient.createProduct(request);
    }

    public ProductResponse updateProduct(
            long id,
            UpdateProductRequest request) {

        return catalogClient.updateProduct(
                id,
                request
        );
    }

    public ProductResponse updateStock(
            long id,
            UpdateProductStockRequest request) {

        return catalogClient.updateStock(
                id,
                request
        );
    }

    public void deactivateProduct(long id) {
        catalogClient.deactivateProduct(id);
    }
}