package cl.duoc.pedidos360.catalog.controller;

import java.util.List;

import cl.duoc.pedidos360.catalog.dto.*;
import cl.duoc.pedidos360.catalog.service.CatalogService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {
    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductResponse> listProducts() {
        return service.listProducts();
    }

    @GetMapping("/{id}")
    public ProductResponse getProduct(@PathVariable @Positive long id) {
        return service.getProduct(id);
    }

    @PostMapping
    @ApiResponse(responseCode = "201", description = "Producto creado correctamente",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductResponse.class)))
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createProduct(request));
    }

    @PutMapping("/{id}")
    public ProductResponse updateProduct(@PathVariable @Positive long id,
            @Valid @RequestBody UpdateProductRequest request) {
        return service.updateProduct(id, request);
    }

    @PatchMapping("/{id}/stock")
    public ProductResponse updateStock(@PathVariable @Positive long id,
            @Valid @RequestBody UpdateProductStockRequest request) {
        return service.updateStock(id, request);
    }

    @DeleteMapping("/{id}")
    @ApiResponse(responseCode = "204", description = "Producto desactivado correctamente", content = @Content)
    public ResponseEntity<Void> deactivateProduct(@PathVariable @Positive long id) {
        service.deactivateProduct(id);
        return ResponseEntity.noContent().build();
    }
}
