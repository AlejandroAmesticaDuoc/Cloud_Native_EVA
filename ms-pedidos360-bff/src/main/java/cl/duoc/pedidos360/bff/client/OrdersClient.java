package cl.duoc.pedidos360.bff.client;

import java.util.List;
import java.util.function.Supplier;

import cl.duoc.pedidos360.bff.dto.order.CreateOrderCommand;
import cl.duoc.pedidos360.bff.dto.order.OrderResponse;
import cl.duoc.pedidos360.bff.dto.order.UpdateOrderStatusRequest;
import cl.duoc.pedidos360.bff.exception.DownstreamServiceException;
import cl.duoc.pedidos360.bff.exception.ForbiddenOperationException;
import cl.duoc.pedidos360.bff.exception.ResourceConflictException;
import cl.duoc.pedidos360.bff.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class OrdersClient {

    private static final String ORDERS_PATH =
            "/api/v1/orders";

    private static final String INVALID_REQUEST_MESSAGE =
            "La solicitud de pedido no es válida";

    private static final String NOT_FOUND_MESSAGE =
            "Pedido no encontrado";

    private static final String CONFLICT_MESSAGE =
            "Existe un conflicto al procesar el pedido";

    private static final String UNAVAILABLE_MESSAGE =
            "El servicio de pedidos no está disponible";

    private static final ParameterizedTypeReference<List<OrderResponse>>
            ORDER_LIST_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public OrdersClient(
            @Qualifier("ordersRestClient")
            RestClient restClient) {

        this.restClient = restClient
                .mutate()
                .defaultStatusHandler(
                        status -> status.value()
                                == HttpStatus.BAD_REQUEST.value(),
                        (request, response) -> {
                            throw new IllegalArgumentException(
                                    INVALID_REQUEST_MESSAGE
                            );
                        }
                )
                .defaultStatusHandler(
                        status -> status.value()
                                == HttpStatus.NOT_FOUND.value(),
                        (request, response) -> {
                            throw new ResourceNotFoundException(
                                    NOT_FOUND_MESSAGE
                            );
                        }
                )
                .defaultStatusHandler(
                        status -> status.value()
                                == HttpStatus.CONFLICT.value(),
                        (request, response) -> {
                            throw new ResourceConflictException(
                                    CONFLICT_MESSAGE
                            );
                        }
                )
                .defaultStatusHandler(
                        status -> status.value() == HttpStatus.FORBIDDEN.value(),
                        (request, response) -> {
                            throw new ForbiddenOperationException("El usuario no tiene permisos para acceder a este pedido");
                        }
                )
                .defaultStatusHandler(
                        HttpStatusCode::isError,
                        (request, response) -> {
                            throw new DownstreamServiceException(
                                    UNAVAILABLE_MESSAGE
                            );
                        }
                )
                .build();
    }

    public List<OrderResponse> listOrders() {
        return execute(() -> requireBody(
                restClient
                        .get()
                        .uri(ORDERS_PATH)
                        .retrieve()
                        .body(ORDER_LIST_TYPE)
        ));
    }

    public List<OrderResponse> listOrdersByCustomerId(
            String customerId) {

        return execute(() -> requireBody(
                restClient
                        .get()
                        .uri(uriBuilder -> uriBuilder
                                .path(ORDERS_PATH)
                                .queryParam(
                                        "customerId",
                                        customerId
                                )
                                .build())
                        .retrieve()
                        .body(ORDER_LIST_TYPE)
        ));
    }

    public OrderResponse getOrder(long id) {
        return execute(() -> requireBody(
                restClient
                        .get()
                        .uri(
                                ORDERS_PATH + "/{id}",
                                id
                        )
                        .retrieve()
                        .body(OrderResponse.class)
        ));
    }

    public OrderResponse createOrder(
            CreateOrderCommand command) {

        return execute(() -> requireBody(
                restClient
                        .post()
                        .uri(ORDERS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(command)
                        .retrieve()
                        .body(OrderResponse.class)
        ));
    }

    public OrderResponse updateStatus(
            long id,
            UpdateOrderStatusRequest request) {

        return execute(() -> requireBody(
                restClient
                        .patch()
                        .uri(
                                ORDERS_PATH + "/{id}/status",
                                id
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(OrderResponse.class)
        ));
    }

    public void cancelOrder(long id) {
        execute(() -> restClient
                .post()
                .uri(
                        ORDERS_PATH + "/{id}/cancel",
                        id
                )
                .retrieve()
                .toBodilessEntity()
        );
    }

    private <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RestClientException exception) {
            throw new DownstreamServiceException(
                    UNAVAILABLE_MESSAGE,
                    exception
            );
        }
    }

    private <T> T requireBody(T body) {
        if (body == null) {
            throw new DownstreamServiceException(
                    UNAVAILABLE_MESSAGE
            );
        }

        return body;
    }
}
