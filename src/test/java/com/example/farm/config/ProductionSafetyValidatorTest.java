package com.example.farm.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSafetyValidatorTest {

    @Test
    void acceptsCompleteProductionConfiguration() {
        assertThatCode(() -> validValidator().validate()).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingRequiredProductionSecret() {
        ProductionSafetyValidator validator = validValidator();
        set(validator, "jwtSecretKey", "");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET_KEY");
    }

    @Test
    void rejectsDevelopmentDefaultJwtSecret() {
        ProductionSafetyValidator validator = validValidator();
        set(validator, "jwtSecretKey", "Farm3DPrinterSuperSecretKey");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("开发默认密钥");
    }

    @Test
    void rejectsWildcardCorsOrigin() {
        ProductionSafetyValidator validator = validValidator();
        set(validator, "corsAllowedOrigins", "http://localhost:3000,*");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORS");
    }

    @Test
    void rejectsExposedApiDocumentation() {
        ProductionSafetyValidator validator = validValidator();
        set(validator, "swaggerEnabled", true);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Swagger");
    }

    private ProductionSafetyValidator validValidator() {
        ProductionSafetyValidator validator = new ProductionSafetyValidator();
        set(validator, "jwtSecretKey", "jwt-production-secret");
        set(validator, "adminSecretKey", "admin-production-secret");
        set(validator, "corsAllowedOrigins", "http://localhost:3000");
        set(validator, "apiDocsEnabled", false);
        set(validator, "swaggerEnabled", false);
        return validator;
    }

    private void set(ProductionSafetyValidator validator, String field, Object value) {
        ReflectionTestUtils.setField(validator, field, value);
    }
}
