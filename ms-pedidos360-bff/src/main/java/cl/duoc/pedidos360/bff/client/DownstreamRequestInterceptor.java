package cl.duoc.pedidos360.bff.client;

import cl.duoc.pedidos360.bff.config.TraceIdFilter;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class DownstreamRequestInterceptor
        implements ClientHttpRequestInterceptor {

    public static final String TRACE_ID_HEADER =
            "X-Trace-Id";


    @Override
    public ClientHttpResponse intercept(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution)
            throws IOException {

        JwtAuthenticationToken authentication =
                resolveJwtAuthentication();

        request.getHeaders().setBearerAuth(
                authentication.getToken().getTokenValue()
        );

        request.getHeaders().set(
                TRACE_ID_HEADER,
                resolveTraceId()
        );

        return execution.execute(request, body);
    }

    private JwtAuthenticationToken resolveJwtAuthentication() {
        Authentication authentication = SecurityContextHolder
                .getContext()
                .getAuthentication();

        if (!(authentication
                instanceof JwtAuthenticationToken jwtAuthentication)
                || !jwtAuthentication.isAuthenticated()) {

            throw new IllegalStateException(
                    "No existe un JWT autenticado "
                            + "para la llamada interna"
            );
        }

        return jwtAuthentication;
    }

    private String resolveTraceId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return TraceIdFilter.resolve(servletAttributes.getRequest());
        }
        return UUID.randomUUID().toString();
    }
}
