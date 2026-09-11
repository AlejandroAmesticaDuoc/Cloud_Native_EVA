package cl.duoc.pedidos360.bff.client;

import java.util.List;
import java.util.function.Supplier;

import cl.duoc.pedidos360.bff.dto.catalog.CreateProductRequest;
import cl.duoc.pedidos360.bff.dto.catalog.ProductResponse;
import cl.duoc.pedidos360.bff.dto.catalog.UpdateProductRequest;
import cl.duoc.pedidos360.bff.dto.catalog.UpdateProductStockRequest;
import cl.duoc.pedidos360.bff.exception.DownstreamServiceException;
import cl.duoc.pedidos360.bff.exception.ResourceConflictException;
import cl.duoc.pedidos360.bff.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class CatalogClient {

    private static final String CATALOG_PATH =
            "/api/v1/catalog";

    private static final String INVALID_REQUEST_MESSAGE =
            "La solicitud de catálogo no es válida";

    private static final String NOT_FOUND_MESSAGE =
            "Producto no encontrado";

    private static final String CONFLICT_MESSAGE =
            "Existe un conflicto al procesar el producto";

    private static final String UNAVAILABLE_MESSAGE =
            "El servicio de catálogo no está disponible";

    private static final ParameterizedTypeReference<List<ProductResponse>>
            PRODUCT_LIST_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public CatalogClient(
            @Qualifier("catalogRestClient")
            RestClient restClient) {

        this.restClient = restClient
                .mutate()
                .defaultStatusHandler(
                        status -> status.value()
                                == HttpStatus.BAD_REQUEST.value(),
                        (request, response) -> {
                            throw new IllegalArgumentException(
                                    INVALID_REQUEST_MESSAGE
                            );
                        }
                )
                .defaultStatusHandler(
                        status -> status.value()
                                == HttpStatus.NOT_FOUND.value(),
                        (request, response) -> {
                            throw new ResourceNotFoundException(
                                    NOT_FOUND_MESSAGE
                            );
                        }
                )
                .defaultStatusHandler(
                        status -> status.value()
                                == HttpStatus.CONFLICT.value(),
                        (request, response) -> {
                            throw new ResourceConflictException(
                                    CONFLICT_MESSAGE
                            );
                        }
                )
                .defaultStatusHandler(
                        HttpStatusCode::isError,
                        (request, response) -> {
                            throw new DownstreamServiceException(
                                    UNAVAILABLE_MESSAGE
                            );
                        }
                )
                .build();
    }

    public List<ProductResponse> listProducts() {
        return execute(() -> requireBody(
                restClient
                        .get()
                        .uri(CATALOG_PATH)
                        .retrieve()
                        .body(PRODUCT_LIST_TYPE)
        ));
    }

    public ProductResponse getProduct(long id) {
        return execute(() -> requireBody(
                restClient
                        .get()
                        .uri(
                                CATALOG_PATH + "/{id}",
                                id
                        )
                        .retrieve()
                        .body(ProductResponse.class)
        ));
    }

    public ProductResponse createProduct(
            CreateProductRequest request) {

        return execute(() -> requireBody(
                restClient
                        .post()
                        .uri(CATALOG_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(ProductResponse.class)
        ));
    }

    public ProductResponse updateProduct(
            long id,
            UpdateProductRequest request) {

        return execute(() -> requireBody(
                restClient
                        .put()
                        .uri(
                                CATALOG_PATH + "/{id}",
                                id
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(ProductResponse.class)
        ));
    }

    public ProductResponse updateStock(
            long id,
            UpdateProductStockRequest request) {

        return execute(() -> requireBody(
                restClient
                        .patch()
                        .uri(
                                CATALOG_PATH + "/{id}/stock",
                                id
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(ProductResponse.class)
        ));
    }

    public void deactivateProduct(long id) {
        execute(() -> restClient
                .delete()
                .uri(
                        CATALOG_PATH + "/{id}",
                        id
                )
                .retrieve()
                .toBodilessEntity()
        );
    }

    private <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RestClientException exception) {
            throw new DownstreamServiceException(
                    UNAVAILABLE_MESSAGE,
                    exception
            );
        }
    }

    private <T> T requireBody(T body) {
        if (body == null) {
            throw new DownstreamServiceException(
                    UNAVAILABLE_MESSAGE
            );
        }

        return body;
    }
}