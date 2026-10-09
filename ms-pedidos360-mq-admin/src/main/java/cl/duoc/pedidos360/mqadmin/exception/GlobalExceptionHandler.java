package cl.duoc.pedidos360.mqadmin.exception;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import cl.duoc.pedidos360.mqadmin.config.TraceIdFilter;
import cl.duoc.pedidos360.mqadmin.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Traducción centralizada de errores a HTTP con el mismo formato que los demás servicios
 * ({@link ApiErrorResponse}). Los errores de validación incluyen el campo y un mensaje claro.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String INVALID = "La solicitud contiene datos inválidos: ";
    /** Si un campo falta, sus demás reglas son consecuencia: se informa solo "obligatorio". */
    private static final Set<String> REQUIRED_CODES = Set.of("NotNull", "NotBlank", "NotEmpty");

    // ------------------------------------------------------------ dominio

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Object> notFound(ResourceNotFoundException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(ResourceConflictException.class)
    ResponseEntity<Object> conflict(ResourceConflictException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidAdminRequestException.class)
    ResponseEntity<Object> invalidConfiguration(InvalidAdminRequestException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(BrokerUnavailableException.class)
    ResponseEntity<Object> unavailable(BrokerUnavailableException exception, HttpServletRequest request) {
        LOG.warn("RabbitMQ no disponible: {} traceId={}", exception.getMessage(), TraceIdFilter.resolve(request));
        return response(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), request);
    }

    /** Respaldo: un error AMQP que no pasó por el servicio igual se informa como broker no disponible. */
    @ExceptionHandler(AmqpException.class)
    ResponseEntity<Object> amqp(AmqpException exception, HttpServletRequest request) {
        LOG.warn("Error AMQP {} traceId={}", exception.getClass().getSimpleName(), TraceIdFilter.resolve(request));
        return response(HttpStatus.SERVICE_UNAVAILABLE, "RabbitMQ no está disponible", request);
    }

    // ------------------------------------------------------------ validación

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest webRequest) {
        return badRequest(INVALID + describe(exception.getBindingResult()), webRequest);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest webRequest) {
        Map<String, List<String>> errors = new TreeMap<>();
        exception.getParameterValidationResults().forEach(result -> {
            if (result instanceof ParameterErrors parameterErrors) {
                describe(parameterErrors.getFieldErrors(), errors);
            } else {
                String name = result.getMethodParameter().getParameterName();
                result.getResolvableErrors().stream().map(MessageSourceResolvable::getDefaultMessage)
                        .forEach(message -> errors.computeIfAbsent(name, key -> new ArrayList<>()).add(message));
            }
        });
        return badRequest(INVALID + join(errors), webRequest);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> constraintViolation(ConstraintViolationException exception, HttpServletRequest request) {
        Map<String, List<String>> errors = new TreeMap<>();
        exception.getConstraintViolations().stream()
                .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .forEach(violation -> errors.computeIfAbsent(lastNode(violation), key -> new ArrayList<>())
                        .add(violation.getMessage()));
        return response(HttpStatus.BAD_REQUEST, INVALID + join(errors), request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest webRequest) {
        return badRequest(unreadable(exception), webRequest);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest webRequest) {
        return badRequest("Falta el parámetro obligatorio '%s'".formatted(exception.getParameterName()), webRequest);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest webRequest) {
        String name = exception.getPropertyName() == null ? "parámetro" : exception.getPropertyName();
        return badRequest("El valor '%s' no es válido para '%s'".formatted(exception.getValue(), name), webRequest);
    }

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

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, HttpServletRequest request) {
        LOG.error("Error de tipo {}. traceId={}", exception.getClass().getSimpleName(), TraceIdFilter.resolve(request),
                exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Ocurrió un error interno inesperado", request);
    }

    // ------------------------------------------------------------ mensajes

    /** JSON mal formado, tipo incorrecto, valor de enum inexistente o propiedad desconocida. */
    private static String unreadable(HttpMessageNotReadableException exception) {
        Throwable cause = exception.getMostSpecificCause();
        if (cause instanceof UnrecognizedPropertyException unknown) {
            return "La propiedad '%s' no es reconocida; propiedades permitidas: %s".formatted(
                    unknown.getPropertyName(), unknown.getKnownPropertyIds().stream()
                            .map(String::valueOf).sorted().collect(Collectors.joining(", ")));
        }
        if (cause instanceof InvalidFormatException invalid && invalid.getTargetType() != null
                && invalid.getTargetType().isEnum()) {
            return "El valor '%s' no es válido para '%s'; valores permitidos: %s".formatted(invalid.getValue(),
                    path(invalid), Arrays.stream(invalid.getTargetType().getEnumConstants())
                            .map(String::valueOf).collect(Collectors.joining(", ")));
        }
        if (cause instanceof MismatchedInputException mismatched && !mismatched.getPath().isEmpty()) {
            return "El campo '%s' tiene un tipo de dato inválido".formatted(path(mismatched));
        }
        if (cause instanceof StreamReadException) {
            return "El cuerpo de la solicitud no es un JSON válido";
        }
        return "El cuerpo de la solicitud es obligatorio y debe ser un JSON válido";
    }

    private static String path(JacksonException exception) {
        return exception.getPath().stream().map(reference -> reference.getPropertyName() != null
                ? reference.getPropertyName() : "[" + reference.getIndex() + "]").collect(Collectors.joining("."));
    }

    private static String describe(BindingResult result) {
        Map<String, List<String>> errors = new TreeMap<>();
        describe(result.getFieldErrors(), errors);
        result.getGlobalErrors().forEach(error -> errors.computeIfAbsent(error.getObjectName(),
                key -> new ArrayList<>()).add(error.getDefaultMessage()));
        return join(errors);
    }

    private static void describe(List<FieldError> fieldErrors, Map<String, List<String>> errors) {
        Map<String, List<FieldError>> byField = fieldErrors.stream()
                .collect(Collectors.groupingBy(FieldError::getField, LinkedHashMap::new, Collectors.toList()));
        byField.forEach((field, list) -> {
            List<FieldError> required = list.stream().filter(error -> REQUIRED_CODES.contains(error.getCode())).toList();
            (required.isEmpty() ? list : required).stream().map(FieldError::getDefaultMessage).sorted()
                    .forEach(message -> errors.computeIfAbsent(field, key -> new ArrayList<>()).add(message));
        });
    }

    private static String join(Map<String, List<String>> errors) {
        return errors.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + String.join(", ", entry.getValue().stream().distinct().toList()))
                .collect(Collectors.joining("; "));
    }

    private static String lastNode(ConstraintViolation<?> violation) {
        String name = null;
        for (Path.Node node : violation.getPropertyPath()) {
            name = node.getName();
        }
        return name == null ? "solicitud" : name;
    }

    private ResponseEntity<Object> badRequest(String message, WebRequest webRequest) {
        return response(HttpStatus.BAD_REQUEST, message, ((ServletWebRequest) webRequest).getRequest());
    }

    private static ResponseEntity<Object> response(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(error(status, message, request));
    }

    private static ApiErrorResponse error(HttpStatusCode status, String message, HttpServletRequest request) {
        return new ApiErrorResponse(Instant.now(), status.value(), HttpStatus.valueOf(status.value()).getReasonPhrase(),
                message, request.getRequestURI(), TraceIdFilter.resolve(request));
    }
}
