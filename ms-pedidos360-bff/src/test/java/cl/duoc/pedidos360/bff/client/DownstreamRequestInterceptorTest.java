package cl.duoc.pedidos360.bff.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class DownstreamRequestInterceptorTest {

    private final DownstreamRequestInterceptor interceptor =
            new DownstreamRequestInterceptor();

    @AfterEach
    void cleanContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldPropagateValidatedJwtAndTraceId()
            throws IOException {

        setAuthenticatedJwt("jwt-validado");

        MockHttpServletRequest incomingRequest =
                new MockHttpServletRequest();

        incomingRequest.addHeader(
                HttpHeaders.AUTHORIZATION,
                "Bearer token-falso"
        );

        incomingRequest.addHeader(
                DownstreamRequestInterceptor.TRACE_ID_HEADER,
                "trace-pedido-123"
        );

        bindRequest(incomingRequest);

        MockClientHttpRequest outgoingRequest =
                newOutgoingRequest();

        outgoingRequest.getHeaders().setBearerAuth(
                "token-anterior"
        );

        interceptor.intercept(
                outgoingRequest,
                new byte[0],
                (request, body) -> {
                    assertEquals(
                            "Bearer jwt-validado",
                            request.getHeaders().getFirst(
                                    HttpHeaders.AUTHORIZATION
                            )
                    );

                    assertEquals(
                            "trace-pedido-123",
                            request.getHeaders().getFirst(
                                    DownstreamRequestInterceptor
                                            .TRACE_ID_HEADER
                            )
                    );

                    return okResponse();
                }
        );
    }

    @Test
    void shouldGenerateAndReuseTraceIdWhenMissing()
            throws IOException {

        setAuthenticatedJwt("jwt-validado");
        bindRequest(new MockHttpServletRequest());

        AtomicReference<String> firstTraceId =
                new AtomicReference<>();

        interceptor.intercept(
                newOutgoingRequest(),
                new byte[0],
                (request, body) -> {
                    firstTraceId.set(
                            request.getHeaders().getFirst(
                                    DownstreamRequestInterceptor
                                            .TRACE_ID_HEADER
                            )
                    );

                    return okResponse();
                }
        );

        UUID.fromString(firstTraceId.get());

        interceptor.intercept(
                newOutgoingRequest(),
                new byte[0],
                (request, body) -> {
                    assertEquals(
                            firstTraceId.get(),
                            request.getHeaders().getFirst(
                                    DownstreamRequestInterceptor
                                            .TRACE_ID_HEADER
                            )
                    );

                    return okResponse();
                }
        );
    }

    @Test
    void shouldReplaceUnsafeTraceId()
            throws IOException {

        setAuthenticatedJwt("jwt-validado");

        MockHttpServletRequest incomingRequest =
                new MockHttpServletRequest();

        incomingRequest.addHeader(
                DownstreamRequestInterceptor.TRACE_ID_HEADER,
                "trace con espacios"
        );

        bindRequest(incomingRequest);

        interceptor.intercept(
                newOutgoingRequest(),
                new byte[0],
                (request, body) -> {
                    String generatedTraceId =
                            request.getHeaders().getFirst(
                                    DownstreamRequestInterceptor
                                            .TRACE_ID_HEADER
                            );

                    assertNotEquals(
                            "trace con espacios",
                            generatedTraceId
                    );

                    UUID.fromString(generatedTraceId);

                    return okResponse();
                }
        );
    }

    @Test
    void shouldBlockCallWithoutAuthenticatedJwt() {
        AtomicBoolean executed =
                new AtomicBoolean(false);

        assertThrows(
                IllegalStateException.class,
                () -> interceptor.intercept(
                        newOutgoingRequest(),
                        new byte[0],
                        (request, body) -> {
                            executed.set(true);
                            return okResponse();
                        }
                )
        );

        assertFalse(executed.get());
    }

    private void setAuthenticatedJwt(String tokenValue) {
        Jwt jwt = Jwt.withTokenValue(tokenValue)
                .header("alg", "none")
                .subject("usuario-prueba")
                .build();

        SecurityContext securityContext =
                SecurityContextHolder.createEmptyContext();

        securityContext.setAuthentication(
                new JwtAuthenticationToken(
                        jwt,
                        List.of()
                )
        );

        SecurityContextHolder.setContext(securityContext);
    }

    private void bindRequest(
            MockHttpServletRequest request) {

        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request)
        );
    }

    private MockClientHttpRequest newOutgoingRequest() {
        return new MockClientHttpRequest(
                HttpMethod.GET,
                URI.create(
                        "http://orders-service/api/orders"
                )
        );
    }

    private MockClientHttpResponse okResponse() {
        return new MockClientHttpResponse(
                new byte[0],
                HttpStatus.OK
        );
    }
}