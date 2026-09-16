package cl.duoc.pedidos360.bff.client;

import cl.duoc.pedidos360.bff.dto.audit.AuditPage;
import cl.duoc.pedidos360.bff.exception.DownstreamServiceException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class AuditClient {
    private static final String UNAVAILABLE = "El servicio de auditoría no está disponible";
    private final RestClient client;

    public AuditClient(@Qualifier("auditRestClient") RestClient client) {
        this.client = client.mutate().defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
            throw new DownstreamServiceException(UNAVAILABLE);
        }).build();
    }

    public AuditPage find(Long orderId, long afterId, int size) {
        try {
            AuditPage page = client.get().uri(builder -> {
                builder.path("/api/v1/audit");
                if (orderId != null) builder.path("/orders/{orderId}");
                builder.queryParam("afterId", afterId).queryParam("size", size);
                return orderId == null ? builder.build() : builder.build(orderId);
            }).retrieve().body(AuditPage.class);
            if (page == null || page.items() == null) throw new DownstreamServiceException(UNAVAILABLE);
            return page;
        } catch (RestClientException exception) {
            throw new DownstreamServiceException(UNAVAILABLE, exception);
        }
    }
}
