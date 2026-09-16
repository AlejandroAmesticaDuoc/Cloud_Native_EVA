package cl.duoc.pedidos360.orders.security;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import cl.duoc.pedidos360.orders.exception.CatalogUnavailableException;
import org.junit.jupiter.api.Test;

class ServiceTokenProviderTest {
    @Test
    void obtainsAndCachesAnApplicationToken() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            calls.incrementAndGet();
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("POST", exchange.getRequestMethod());
            assertTrue(form.contains("grant_type=client_credentials"));
            assertTrue(form.contains("client_id=orders-test"));
            assertTrue(form.contains("client_secret=test-only-secret"));
            byte[] body = "{\"access_token\":\"test-only-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var provider = new ServiceTokenProvider("orders-test", "test-only-secret",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/token", "api://test/.default");
            assertEquals("test-only-token", provider.token());
            assertEquals("test-only-token", provider.token());
            assertEquals(1, calls.get());
            provider.invalidate();
            assertEquals("test-only-token", provider.token());
            assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }

    @Test
    void rejectsMissingCredentialsWithoutAnAnonymousFallback() {
        var provider = new ServiceTokenProvider("", "", "", "");
        assertThrows(CatalogUnavailableException.class, provider::token);
    }

    @Test
    void requiresTlsExceptForLoopbackTests() {
        assertThrows(IllegalArgumentException.class, () -> new ServiceTokenProvider(
                "id", "secret", "http://example.test/token", "api://test/.default"));
    }

    @Test
    void doesNotExposeIdentityProviderErrors() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":\"invalid_client\",\"error_description\":\"detalle secreto\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var provider = new ServiceTokenProvider("id", "secret",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/token", "api://test/.default");
            var error = assertThrows(CatalogUnavailableException.class, provider::token);
            assertFalse(error.getMessage().contains("detalle secreto"));
        } finally { server.stop(0); }
    }
}
