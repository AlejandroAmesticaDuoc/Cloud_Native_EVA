package cl.duoc.pedidos360.bff.client;

import java.util.function.Supplier;
import cl.duoc.pedidos360.bff.dto.report.*;
import cl.duoc.pedidos360.bff.exception.DownstreamServiceException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class ReportsClient {
    private static final String UNAVAILABLE = "El servicio de reportería no está disponible";
    private final RestClient client;

    public ReportsClient(@Qualifier("reportRestClient") RestClient client) {
        this.client = client.mutate().defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
            throw new DownstreamServiceException(UNAVAILABLE);
        }).build();
    }

    public SummaryResponse summary(int hours) {
        SummaryResponse result = execute(() -> client.get()
                .uri(builder -> builder.path("/api/v1/reports/summary").queryParam("hours", hours).build())
                .retrieve().body(SummaryResponse.class));
        if (result.ordersByStatus() == null || result.salesByHour() == null || result.deliveredAmount() == null
                || result.hourlyFrom() == null || result.hourlyTo() == null) {
            throw new DownstreamServiceException(UNAVAILABLE);
        }
        return result;
    }

    public LeadTimeResponse leadTime() {
        LeadTimeResponse result = execute(() -> client.get().uri("/api/v1/reports/lead-time")
                .retrieve().body(LeadTimeResponse.class));
        if (result.deliveredOrders() == null) throw new DownstreamServiceException(UNAVAILABLE);
        return result;
    }

    private <T> T execute(Supplier<T> operation) {
        try {
            T result = operation.get();
            if (result == null) throw new DownstreamServiceException(UNAVAILABLE);
            return result;
        } catch (RestClientException exception) {
            throw new DownstreamServiceException(UNAVAILABLE, exception);
        }
    }
}
