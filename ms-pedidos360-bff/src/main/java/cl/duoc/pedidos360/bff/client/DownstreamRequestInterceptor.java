package cl.duoc.pedidos360.bff.client;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
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

    private static final String TRACE_ID_ATTRIBUTE =
            DownstreamRequestInterceptor.class.getName()
                    + ".traceId";

    private static final Pattern SAFE_TRACE_ID =
            Pattern.compile("[A-Za-z0-9._-]{1,100}");

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
        RequestAttributes requestAttributes =
                RequestContextHolder.getRequestAttributes();

        if (!(requestAttributes
                instanceof ServletRequestAttributes servletAttributes)) {
            return UUID.randomUUID().toString();
        }

        HttpServletRequest servletRequest =
                servletAttributes.getRequest();

        Object storedTraceId = servletRequest.getAttribute(
                TRACE_ID_ATTRIBUTE
        );

        if (storedTraceId instanceof String traceId) {
            return traceId;
        }

        String receivedTraceId = servletRequest.getHeader(
                TRACE_ID_HEADER
        );

        String traceId = isSafeTraceId(receivedTraceId)
                ? receivedTraceId
                : UUID.randomUUID().toString();

        servletRequest.setAttribute(
                TRACE_ID_ATTRIBUTE,
                traceId
        );

        return traceId;
    }

    private boolean isSafeTraceId(String traceId) {
        return traceId != null
                && SAFE_TRACE_ID.matcher(traceId).matches();
    }
}