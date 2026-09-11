package cl.duoc.pedidos360.bff.dto.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OrderDtoValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory =
                Validation.buildDefaultValidatorFactory();

        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    void shouldAcceptValidCreateOrderRequest() {
        CreateOrderRequest request =
                new CreateOrderRequest(List.of(
                        new CreateOrderItemRequest(
                                10L,
                                2
                        )
                ));

        assertTrue(
                validator.validate(request).isEmpty()
        );
    }

    @Test
    void shouldRejectOrderWithoutItems() {
        CreateOrderRequest request =
                new CreateOrderRequest(List.of());

        assertEquals(
                1,
                validator.validate(request).size()
        );
    }

    @Test
    void shouldRejectInvalidOrderItem() {
        CreateOrderRequest request =
                new CreateOrderRequest(List.of(
                        new CreateOrderItemRequest(
                                -1L,
                                0
                        )
                ));

        assertEquals(
                2,
                validator.validate(request).size()
        );
    }

    @Test
    void shouldRejectMoreThanFiftyItems() {
        List<CreateOrderItemRequest> items =
                IntStream.range(0, 51)
                        .mapToObj(index ->
                                new CreateOrderItemRequest(
                                        (long) index + 1,
                                        1
                                ))
                        .toList();

        CreateOrderRequest request =
                new CreateOrderRequest(items);

        assertEquals(
                1,
                validator.validate(request).size()
        );
    }

    @Test
    void shouldRejectMissingOrderStatus() {
        UpdateOrderStatusRequest request =
                new UpdateOrderStatusRequest(null);

        assertEquals(
                1,
                validator.validate(request).size()
        );
    }
}