package com.meridian.payments.intake.provider.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.meridian.payments.intake.canonical.Money;
import com.meridian.payments.intake.canonical.PolicyReference;
import com.meridian.payments.intake.canonical.ReferenceStatus;
import com.meridian.payments.intake.provider.MalformedNotificationException;
import com.meridian.payments.intake.provider.UnsupportedNotificationException;

class SupportTest {

    @Nested
    class PolicyReferences {

        private final PolicyReferenceNormalizer normalizer = new PolicyReferenceNormalizer();

        @ParameterizedTest(name = "[{0}] in {1} -> {2} {3}")
        @CsvSource(nullValues = "null", value = {
                "POL-CO-000187342,     CO, POL-CO-000187342, VALID",
                "pol-co-000198004,     CO, POL-CO-000198004, NORMALIZED",
                "000215560,            CO, POL-CO-000215560, NORMALIZED",
                "'  POL-CO-000187342 ', CO, POL-CO-000187342, NORMALIZED",
                "'POL-CO- 000187342',  CO, POL-CO-000187342, NORMALIZED",
                "POL-MX-000412785,     CO, null,             INVALID",
                "POL-CO-00018734,      CO, null,             INVALID",
                "0002155601,           CO, null,             INVALID",
                "POLCO000187342,       CO, null,             INVALID",
                "'',                   CO, null,             INVALID",
                "POL-MX-000412785,     MX, POL-MX-000412785, VALID"
        })
        void normalizesOnlyUnambiguousVariants(String reference, String country, String number, ReferenceStatus status) {
            PolicyReference result = normalizer.normalize(reference, country);

            assertThat(result.number()).isEqualTo(number);
            assertThat(result.referenceStatus()).isEqualTo(status);
            assertThat(result.reference()).isEqualTo(reference);
        }

        @Test
        void nullReferenceIsInvalid() {
            assertThat(normalizer.normalize(null, "CO").referenceStatus()).isEqualTo(ReferenceStatus.INVALID);
        }
    }

    @Nested
    class Money_ {

        @Test
        void usesTheCurrencyExponent() {
            assertThat(Amounts.fromMinorUnits(125000, "MXN")).isEqualTo(new Money("1250.00", "MXN"));
            assertThat(Amounts.fromMinorUnits(45990, "CLP")).isEqualTo(new Money("45990", "CLP"));
            assertThat(Amounts.fromMajorUnits(new BigDecimal("185000"), "COP")).isEqualTo(new Money("185000.00", "COP"));
        }

        @Test
        void neverRoundsSilently() {
            assertThatThrownBy(() -> Amounts.fromMajorUnits(new BigDecimal("45990.50"), "CLP"))
                    .isInstanceOf(MalformedNotificationException.class);
        }

        @Test
        void rejectsNegativeAndUnknownCurrencies() {
            assertThatThrownBy(() -> Amounts.fromMinorUnits(-1, "MXN")).isInstanceOf(MalformedNotificationException.class);
            assertThatThrownBy(() -> Amounts.fromMinorUnits(100, "USD")).isInstanceOf(UnsupportedNotificationException.class);
        }
    }
}
