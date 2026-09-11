package cl.duoc.pedidos360.catalog.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CatalogDtoValidationTest {

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
    void shouldAcceptValidCreateProductRequest() {
        CreateProductRequest request =
                new CreateProductRequest(
                        "Café americano",
                        BigDecimal.valueOf(2500),
                        20
                );

        assertTrue(
                validator.validate(request).isEmpty()
        );
    }

    @Test
    void shouldRejectMissingCreateProductFields() {
        CreateProductRequest request =
                new CreateProductRequest(
                        null,
                        null,
                        null
                );

        assertEquals(
                3,
                validator.validate(request).size()
        );
    }

    @Test
    void shouldRejectInvalidCreateProductValues() {
        CreateProductRequest request =
                new CreateProductRequest(
                        "   ",
                        BigDecimal.ZERO,
                        -1
                );

        assertEquals(
                3,
                validator.validate(request).size()
        );
    }

    @Test
    void shouldRejectProductNameLongerThanOneHundredCharacters() {
        CreateProductRequest request =
                new CreateProductRequest(
                        "a".repeat(101),
                        BigDecimal.valueOf(2500),
                        20
                );

        assertEquals(
                1,
                validator.validate(request).size()
        );
    }

    @Test
    void shouldRejectPriceWithMoreThanTwoDecimals() {
        CreateProductRequest request =
                new CreateProductRequest(
                        "Café americano",
                        new BigDecimal("2500.999"),
                        20
                );

        assertEquals(
                1,
                validator.validate(request).size()
        );
    }

    @Test
    void shouldAcceptValidUpdateProductRequest() {
        UpdateProductRequest request =
                new UpdateProductRequest(
                        "Café americano grande",
                        BigDecimal.valueOf(3200)
                );

        assertTrue(
                validator.validate(request).isEmpty()
        );
    }

    @Test
    void shouldRejectInvalidUpdateProductRequest() {
        UpdateProductRequest request =
                new UpdateProductRequest(
                        "",
                        BigDecimal.valueOf(-1)
                );

        assertEquals(
                2,
                validator.validate(request).size()
        );
    }

    @Test
    void shouldValidateStockUpdateRequest() {
        UpdateProductStockRequest validRequest =
                new UpdateProductStockRequest(0);

        UpdateProductStockRequest missingStock =
                new UpdateProductStockRequest(null);

        UpdateProductStockRequest negativeStock =
                new UpdateProductStockRequest(-1);

        assertTrue(
                validator.validate(validRequest).isEmpty()
        );

        assertEquals(
                1,
                validator.validate(missingStock).size()
        );

        assertEquals(
                1,
                validator.validate(negativeStock).size()
        );
    }
}