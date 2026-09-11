package cl.duoc.pedidos360.bff.controller;

import java.util.List;

import cl.duoc.pedidos360.bff.dto.catalog.CreateProductRequest;
import cl.duoc.pedidos360.bff.dto.catalog.ProductResponse;
import cl.duoc.pedidos360.bff.dto.catalog.UpdateProductRequest;
import cl.duoc.pedidos360.bff.dto.catalog.UpdateProductStockRequest;
import cl.duoc.pedidos360.bff.service.CatalogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public List<ProductResponse> listProducts() {
        return catalogService.listProducts();
    }

    @GetMapping("/{id}")
    public ProductResponse getProduct(
            @PathVariable("id")
            @Positive(message =
                    "El identificador del producto debe ser positivo")
            long id) {

        return catalogService.getProduct(id);
    }

    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(
            @Valid
            @RequestBody
            CreateProductRequest request) {

        ProductResponse response =
                catalogService.createProduct(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PutMapping("/{id}")
    public ProductResponse updateProduct(
            @PathVariable("id")
            @Positive(message =
                    "El identificador del producto debe ser positivo")
            long id,
            @Valid
            @RequestBody
            UpdateProductRequest request) {

        return catalogService.updateProduct(
                id,
                request
        );
    }

    @PatchMapping("/{id}/stock")
    public ProductResponse updateStock(
            @PathVariable("id")
            @Positive(message =
                    "El identificador del producto debe ser positivo")
            long id,
            @Valid
            @RequestBody
            UpdateProductStockRequest request) {

        return catalogService.updateStock(
                id,
                request
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivateProduct(
            @PathVariable("id")
            @Positive(message =
                    "El identificador del producto debe ser positivo")
            long id) {

        catalogService.deactivateProduct(id);

        return ResponseEntity
                .noContent()
                .build();
    }
}