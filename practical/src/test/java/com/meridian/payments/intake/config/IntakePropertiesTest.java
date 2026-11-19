package com.meridian.payments.intake.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;

/** Misconfiguration must stop the service at startup, not lose a payment at runtime. */
class IntakePropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsAreValid() {
        assertThat(validator.validate(new IntakeProperties(65536, Duration.ofSeconds(3), Duration.ofSeconds(10)))).isEmpty();
    }

    @Test
    void publishTimeoutAboveTheProviderTimeoutIsRejected() {
        assertThat(validator.validate(new IntakeProperties(65536, Duration.ofSeconds(6), Duration.ofSeconds(10))))
                .extracting(v -> v.getMessage())
                .containsExactly("publish-timeout must be at most 4s (Andina times out at 5s)");
    }

    @Test
    void leaseShorterThanThePublishTimeoutIsRejected() {
        assertThat(validator.validate(new IntakeProperties(65536, Duration.ofSeconds(3), Duration.ofSeconds(2))))
                .extracting(v -> v.getMessage())
                .containsExactly("claim-lease must be longer than publish-timeout");
    }

    @Test
    void absurdBodyLimitsAreRejected() {
        assertThat(validator.validate(new IntakeProperties(10, Duration.ofSeconds(3), Duration.ofSeconds(10)))).hasSize(1);
    }
}
