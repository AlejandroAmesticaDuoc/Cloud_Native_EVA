package cl.duoc.pedidos360.bff.security;

import cl.duoc.pedidos360.bff.config.TraceIdFilter;

import java.io.IOException;
import java.time.Instant;

import cl.duoc.pedidos360.bff.dto.common.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final ObjectMapper objectMapper;

    private final BearerTokenAccessDeniedHandler delegate =
            new BearerTokenAccessDeniedHandler();

    public JwtAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {

        delegate.handle(
                request,
                response,
                accessDeniedException
        );

        String traceId = resolveTraceId(request);

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                Instant.now(),
                HttpStatus.FORBIDDEN.value(),
                HttpStatus.FORBIDDEN.getReasonPhrase(),
                "El usuario no tiene permisos para realizar esta acción",
                request.getRequestURI(),
                traceId
        );

        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(TRACE_ID_HEADER, traceId);

        objectMapper.writeValue(
                response.getOutputStream(),
                errorResponse
        );
    }

    private String resolveTraceId(HttpServletRequest request) {
        return TraceIdFilter.resolve(request);
    }
}
