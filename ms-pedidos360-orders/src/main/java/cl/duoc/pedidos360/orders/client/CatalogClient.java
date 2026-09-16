package cl.duoc.pedidos360.orders.client;

import java.util.function.Supplier;
import cl.duoc.pedidos360.orders.dto.*;
import cl.duoc.pedidos360.orders.exception.*;
import cl.duoc.pedidos360.orders.security.ServiceTokenProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class CatalogClient {
    private final RestClient client;
    private final ServiceTokenProvider tokens;

    public CatalogClient(@Qualifier("catalogRestClient") RestClient client, ServiceTokenProvider tokens) {
        this.client = client;
        this.tokens = tokens;
    }

    public ProductResponse product(long id, String accessToken, String traceId) {
        try {
            ProductResponse product = client.get().uri("/api/v1/catalog/{id}", id)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .header("X-Trace-Id", traceId).accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(ProductResponse.class);
            if (product == null || product.id() == null || product.id() != id
                    || product.price() == null || product.price().signum() <= 0 || !product.active()) {
                throw new CatalogUnavailableException();
            }
            return product;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new OrderNotFoundException("Uno de los productos no está disponible");
            }
            throw new CatalogUnavailableException();
        } catch (RestClientException exception) {
            throw new CatalogUnavailableException();
        }
    }

    public void deduct(StockDeductionRequest request, String traceId) {
        stock(() -> client.post().uri("/internal/v1/catalog/stock/deductions")
                .headers(headers -> headers.setBearerAuth(tokens.token()))
                .header("X-Trace-Id", traceId).contentType(MediaType.APPLICATION_JSON)
                .body(request).retrieve().toBodilessEntity());
    }

    public void release(long orderId, String traceId) {
        stock(() -> client.post().uri("/internal/v1/catalog/stock/deductions/{id}/release", orderId)
                .headers(headers -> headers.setBearerAuth(tokens.token()))
                .header("X-Trace-Id", traceId).retrieve().toBodilessEntity());
    }

    private void stock(Supplier<org.springframework.http.ResponseEntity<Void>> operation) {
        try {
            if (operation.get().getStatusCode().value() != 204) throw new CatalogUnavailableException();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            boolean stockRejected = status == 409 && exception.getResponseHeaders() != null
                    && "rejected".equals(exception.getResponseHeaders().getFirst("X-Stock-Result"));
            if (status == 400 || status == 404 || stockRejected) throw new StockRejectedException();
            if (status == 401) tokens.invalidate();
            throw new CatalogUnavailableException();
        } catch (RestClientException exception) {
            throw new CatalogUnavailableException();
        }
    }
}
