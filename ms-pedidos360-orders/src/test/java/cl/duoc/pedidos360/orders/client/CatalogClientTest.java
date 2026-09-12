package cl.duoc.pedidos360.orders.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.io.IOException;
import java.util.List;
import cl.duoc.pedidos360.orders.dto.StockDeductionRequest;
import cl.duoc.pedidos360.orders.exception.*;
import cl.duoc.pedidos360.orders.security.ServiceTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CatalogClientTest {
    private CatalogClient client;
    private MockRestServiceServer server;
    private ServiceTokenProvider tokens;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder().baseUrl("http://catalog.test");
        server = MockRestServiceServer.bindTo(builder).build();
        tokens = mock(ServiceTokenProvider.class);
        when(tokens.token()).thenReturn("application-token");
        client = new CatalogClient(builder.build(), tokens);
    }

    @Test
    void readsPricesUsingUserTokenAndTrace() {
        server.expect(requestTo("http://catalog.test/api/v1/catalog/1"))
                .andExpect(header("Authorization", "Bearer user-token")).andExpect(header("X-Trace-Id", "trace-test"))
                .andRespond(withSuccess("{\"id\":1,\"name\":\"Producto\",\"price\":1500.50,\"stock\":1,\"active\":true}",
                        MediaType.APPLICATION_JSON));
        assertEquals("1500.50", client.product(1, "user-token", "trace-test").price().toPlainString());
        verifyNoInteractions(tokens);
        server.verify();
    }

    @Test
    void deductsWithTheServiceIdentityAndCompatibleBody() {
        server.expect(requestTo("http://catalog.test/internal/v1/catalog/stock/deductions"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer application-token"))
                .andExpect(header("X-Trace-Id", "trace-test"))
                .andExpect(content().json("{\"orderId\":20,\"items\":[{\"productId\":1,\"quantity\":2}]}"))
                .andRespond(withNoContent());
        client.deduct(new StockDeductionRequest(20L, List.of(new StockDeductionRequest.Item(1L, 2))), "trace-test");
        server.verify();
    }

    @Test
    void releasesUsingTheServiceIdentity() {
        server.expect(requestTo("http://catalog.test/internal/v1/catalog/stock/deductions/20/release"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer application-token"))
                .andRespond(withNoContent());
        client.release(20, "trace-test");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404})
    void distinguishesDefinitiveRejection(int status) {
        server.expect(anything()).andRespond(withStatus(HttpStatusCode.valueOf(status)).body("detalle SQL privado"));
        assertThrows(StockRejectedException.class, () -> client.release(20, "trace-test"));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 409, 500, 503})
    void treatsUncertainFailuresAsUnavailable(int status) {
        server.expect(anything()).andRespond(withStatus(HttpStatusCode.valueOf(status)).body("detalle privado"));
        assertThrows(CatalogUnavailableException.class, () -> client.release(20, "trace-test"));
        if (status == 401) verify(tokens).invalidate();
    }

    @Test
    void preservesUncertaintyWhenConnectionFails() {
        server.expect(anything()).andRespond(withException(new IOException("internal-host")));
        assertThrows(CatalogUnavailableException.class, () -> client.release(20, "trace-test"));
    }

    @Test
    void distinguishesConfirmedInsufficientStockFromOtherConflicts() {
        var headers = new HttpHeaders();
        headers.add("X-Stock-Result", "rejected");
        server.expect(anything()).andRespond(withStatus(HttpStatus.CONFLICT).headers(headers));
        assertThrows(StockRejectedException.class, () -> client.release(20, "trace-test"));
    }

    @Test
    void rejectsUnexpectedSuccessStatus() {
        server.expect(anything()).andRespond(withSuccess());
        assertThrows(CatalogUnavailableException.class, () -> client.release(20, "trace-test"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"id\":2,\"price\":1,\"active\":true}",
            "{\"id\":1,\"price\":0,\"active\":true}", "{\"id\":1,\"price\":1,\"active\":false}", "not-json"})
    void rejectsInvalidCatalogResponse(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThrows(CatalogUnavailableException.class, () -> client.product(1, "user-token", "trace-test"));
    }

    @Test
    void mapsMissingProduct() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThrows(OrderNotFoundException.class, () -> client.product(1, "user-token", "trace-test"));
    }
}
