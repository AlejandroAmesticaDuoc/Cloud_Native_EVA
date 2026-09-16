package cl.duoc.pedidos360.bff.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import cl.duoc.pedidos360.bff.exception.DownstreamServiceException;

class AuditClientTest {
    private MockRestServiceServer server;
    private AuditClient client;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder().baseUrl("http://audit.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AuditClient(builder.build());
    }

    @Test
    void forwardsGlobalCursorAndReadsPage() {
        server.expect(requestTo("http://audit.test/api/v1/audit?afterId=7&size=2"))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess("""
                        {"items":[{"id":8,"recordedAt":"2026-09-12T12:00:00Z",
                        "event":{"schemaVersion":1,"eventId":"ab7d7193-5b85-4128-a28a-34c222d84014",
                        "eventType":"OrderCreated","orderId":10,"aggregateVersion":1,
                        "occurredAt":"2026-09-12T12:00:00Z","traceId":"audit-test",
                        "actorId":"alice","customerId":"alice","previousStatus":null,"status":"CREADO",
                        "createdAt":"2026-09-12T12:00:00Z","total":100.50}}],"nextAfterId":8}
                        """, MediaType.APPLICATION_JSON));
        var page = client.find(null, 7, 2);
        assertEquals(8L, page.nextAfterId());
        assertEquals("alice", page.items().getFirst().event().actorId());
        assertEquals(10L, page.items().getFirst().event().orderId());
        server.verify();
    }

    @Test
    void filtersByOrderAndAcceptsEmptyHistory() {
        server.expect(requestTo("http://audit.test/api/v1/audit/orders/10?afterId=0&size=50"))
                .andRespond(withSuccess("{\"items\":[],\"nextAfterId\":null}", MediaType.APPLICATION_JSON));
        assertTrue(client.find(10L, 0, 50).items().isEmpty());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 500, 503})
    void hidesInternalErrors(int status) {
        server.expect(requestTo("http://audit.test/api/v1/audit?afterId=0&size=50"))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)).body("internal secret"));
        var error = assertThrows(DownstreamServiceException.class, () -> client.find(null, 0, 50));
        assertEquals("El servicio de auditoría no está disponible", error.getMessage());
        server.verify();
    }

    @Test
    void translatesConnectionError() {
        server.expect(requestTo("http://audit.test/api/v1/audit?afterId=0&size=50"))
                .andRespond(withException(new IOException("internal host")));
        assertThrows(DownstreamServiceException.class, () -> client.find(null, 0, 50));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{invalid"})
    void rejectsInvalidResponses(String body) {
        server.expect(requestTo("http://audit.test/api/v1/audit?afterId=0&size=50"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThrows(DownstreamServiceException.class, () -> client.find(null, 0, 50));
        server.verify();
    }
}
