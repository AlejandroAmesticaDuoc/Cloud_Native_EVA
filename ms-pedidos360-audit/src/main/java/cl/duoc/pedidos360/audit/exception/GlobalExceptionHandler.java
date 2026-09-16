package cl.duoc.pedidos360.audit.exception;

import java.time.Instant;

import cl.duoc.pedidos360.audit.config.TraceIdFilter;
import cl.duoc.pedidos360.audit.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest webRequest) {
        HttpServletRequest request = ((ServletWebRequest) webRequest).getRequest();
        String message = switch (status.value()) {
            case 400 -> "La solicitud contiene datos inválidos";
            case 404 -> "Recurso no encontrado";
            case 405 -> "Método HTTP no permitido";
            case 406 -> "Formato de respuesta no disponible";
            case 415 -> "El contenido debe enviarse como application/json";
            default -> "No se pudo procesar la solicitud";
        };
        return new ResponseEntity<>(error(status, message, request), headers, status);
    }

    @ExceptionHandler({ConstraintViolationException.class, IllegalArgumentException.class})
    ResponseEntity<Object> invalid(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "La solicitud contiene datos inválidos", request);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<Object> unavailable(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "La base de datos no está disponible", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, HttpServletRequest request) {
        LOG.error("Error de tipo {}. traceId={}", exception.getClass().getSimpleName(), TraceIdFilter.resolve(request));
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Ocurrió un error interno inesperado", request);
    }

    private ResponseEntity<Object> response(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(error(status, message, request));
    }

    private ApiErrorResponse error(HttpStatusCode status, String message, HttpServletRequest request) {
        return new ApiErrorResponse(Instant.now(), status.value(), HttpStatus.valueOf(status.value()).getReasonPhrase(),
                message, request.getRequestURI(), TraceIdFilter.resolve(request));
    }
}
