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

class ReportsClientTest {
    private MockRestServiceServer server;
    private ReportsClient client;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder().baseUrl("http://report.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ReportsClient(builder.build());
    }

    @Test
    void forwardsHourlyWindowAndReadsSummary() {
        server.expect(requestTo("http://report.test/api/v1/reports/summary?hours=2"))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess("""
                        {"totalOrders":2,"activeOrders":1,"deliveredOrders":1,"cancelledOrders":0,
                        "ordersByStatus":{"CREADO":1,"ACEPTADO":0,"EN_PREPARACION":0,"DESPACHADO":0,"ENTREGADO":1,"CANCELADO":0},
                        "deliveredAmount":100.50,"hourlyFrom":"2026-09-12T13:00:00Z","hourlyTo":"2026-09-12T15:00:00Z",
                        "salesByHour":[{"hour":"2026-09-12T13:00:00Z","deliveredOrders":1,"deliveredAmount":100.50},
                        {"hour":"2026-09-12T14:00:00Z","deliveredOrders":0,"deliveredAmount":0.00}]}
                        """, MediaType.APPLICATION_JSON));
        var summary = client.summary(2);
        assertEquals(2, summary.totalOrders());
        assertEquals(1, summary.ordersByStatus().get("ENTREGADO"));
        assertEquals(2, summary.salesByHour().size());
        assertEquals("100.50", summary.deliveredAmount().toPlainString());
        server.verify();
    }

    @Test
    void readsLeadTimeWithoutFabricatingAnAverage() {
        server.expect(requestTo("http://report.test/api/v1/reports/lead-time")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"deliveredOrders":0,"averageSeconds":null,"minimumSeconds":null,"maximumSeconds":null}
                        """, MediaType.APPLICATION_JSON));
        var response = client.leadTime();
        assertEquals(0, response.deliveredOrders());
        assertNull(response.averageSeconds());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 500, 503})
    void hidesInternalServiceErrors(int status) {
        for (String path : new String[] {"/summary?hours=24", "/lead-time"}) {
            server.expect(requestTo("http://report.test/api/v1/reports" + path))
                    .andRespond(withStatus(HttpStatusCode.valueOf(status)).body("internal secret"));
        }
        var error = assertThrows(DownstreamServiceException.class, () -> client.summary(24));
        assertEquals("El servicio de reportería no está disponible", error.getMessage());
        assertThrows(DownstreamServiceException.class, client::leadTime);
        server.verify();
    }

    @Test
    void translatesConnectionError() {
        server.expect(requestTo("http://report.test/api/v1/reports/summary?hours=24"))
                .andRespond(withException(new IOException("internal host")));
        assertThrows(DownstreamServiceException.class, () -> client.summary(24));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{invalid"})
    void rejectsIncompleteOrInvalidResponses(String body) {
        server.expect(requestTo("http://report.test/api/v1/reports/summary?hours=24"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://report.test/api/v1/reports/lead-time"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThrows(DownstreamServiceException.class, () -> client.summary(24));
        assertThrows(DownstreamServiceException.class, client::leadTime);
        server.verify();
    }
}
