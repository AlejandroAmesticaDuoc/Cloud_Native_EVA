package cl.duoc.pedidos360.audit.security;

import java.io.IOException;
import java.time.Instant;

import cl.duoc.pedidos360.audit.config.TraceIdFilter;
import cl.duoc.pedidos360.audit.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class AuthenticationErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper objectMapper;

    public AuthenticationErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        new BearerTokenAuthenticationEntryPoint().commence(request, response, exception);
        write(request, response, HttpStatus.UNAUTHORIZED,
                "Se requiere autenticación para acceder a este recurso");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        new BearerTokenAccessDeniedHandler().handle(request, response, exception);
        write(request, response, HttpStatus.FORBIDDEN,
                "El usuario no tiene permisos para realizar esta acción");
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
            HttpStatus status, String message) throws IOException {
        String traceId = TraceIdFilter.resolve(request);
        response.setStatus(status.value());
        response.setHeader(TraceIdFilter.HEADER, traceId);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
                Instant.now(), status.value(), status.getReasonPhrase(), message,
                request.getRequestURI(), traceId));
    }
}
