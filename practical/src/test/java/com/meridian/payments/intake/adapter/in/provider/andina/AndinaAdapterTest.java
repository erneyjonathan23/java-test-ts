package com.meridian.payments.intake.adapter.in.provider.andina;

import static com.meridian.payments.intake.TestFixtures.ANDINA_TEST_SECRET;
import static com.meridian.payments.intake.TestFixtures.AN_APPROVED;
import static com.meridian.payments.intake.TestFixtures.AN_APPROVED_RETRY;
import static com.meridian.payments.intake.TestFixtures.AN_PENDING;
import static com.meridian.payments.intake.TestFixtures.AN_REVERSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.intake.TestFixtures;
import com.meridian.payments.intake.TestFixtures.Vector;
import com.meridian.payments.intake.domain.Money;
import com.meridian.payments.intake.domain.PaymentMethodType;
import com.meridian.payments.intake.domain.PaymentStatus;
import com.meridian.payments.intake.domain.PremiumPaymentData;
import com.meridian.payments.intake.domain.ReferenceStatus;
import com.meridian.payments.intake.domain.MalformedNotificationException;
import com.meridian.payments.intake.application.SignatureVerificationException;
import com.meridian.payments.intake.domain.UnsupportedNotificationException;
import com.meridian.payments.intake.domain.PolicyReferenceNormalizer;

class AndinaAdapterTest {

    private final AndinaAdapter adapter = new AndinaAdapter(
            TestFixtures.secrets(ANDINA_TEST_SECRET), new ObjectMapper(), new PolicyReferenceNormalizer());

    private static byte[] replace(byte[] body, String from, String to) {
        String json = new String(body, StandardCharsets.UTF_8);
        if (!json.contains(from)) {
            throw new IllegalArgumentException("fixture does not contain " + from);
        }
        return json.replace(from, to).getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    class Signature {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.meridian.payments.intake.TestFixtures#allAndina")
        void acceptsEveryPublishedTestVector(Vector vector) {
            assertThatCode(() -> adapter.verifySignature(vector.body(), vector.headers(), vector.signedAt()))
                    .doesNotThrowAnyException();
        }

        @Test
        void rejectsATamperedAmount() {
            byte[] tampered = replace(AN_APPROVED.body(), "185000.00", "985000.00");

            assertThatThrownBy(() -> adapter.verifySignature(tampered, AN_APPROVED.headers(), AN_APPROVED.signedAt()))
                    .isInstanceOf(SignatureVerificationException.class);
        }

        @Test
        void timestampHeaderIsPartOfTheSignedContent() {
            HttpHeaders moved = new HttpHeaders();
            moved.add("X-Andina-Timestamp", "2026-11-28T14:03:12-05:00");
            moved.add("X-Andina-Signature", AN_APPROVED.headers().getFirst("X-Andina-Signature"));

            assertThatThrownBy(() -> adapter.verifySignature(AN_APPROVED.body(), moved, AN_APPROVED.signedAt()))
                    .isInstanceOf(SignatureVerificationException.class);
        }

        @Test
        void sameInstantWrittenInAnotherOffsetIsNotTheSignedString() {
            // 14:03:11-05:00 == 19:03:11Z, but the provider signed the header text, not the instant.
            HttpHeaders utc = new HttpHeaders();
            utc.add("X-Andina-Timestamp", "2026-11-28T19:03:11Z");
            utc.add("X-Andina-Signature", AN_APPROVED.headers().getFirst("X-Andina-Signature"));

            assertThatThrownBy(() -> adapter.verifySignature(AN_APPROVED.body(), utc, AN_APPROVED.signedAt()))
                    .isInstanceOf(SignatureVerificationException.class);
        }

        @Test
        void enforcesTheTenMinuteWindowInBothDirections() {
            Instant signedAt = AN_APPROVED.signedAt();
            assertThatCode(() -> adapter.verifySignature(AN_APPROVED.body(), AN_APPROVED.headers(), signedAt.plus(Duration.ofMinutes(10))))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> adapter.verifySignature(AN_APPROVED.body(), AN_APPROVED.headers(), signedAt.plus(Duration.ofMinutes(10)).plusSeconds(1)))
                    .isInstanceOf(SignatureVerificationException.class);
            assertThatThrownBy(() -> adapter.verifySignature(AN_APPROVED.body(), AN_APPROVED.headers(), signedAt.minus(Duration.ofMinutes(11))))
                    .isInstanceOf(SignatureVerificationException.class);
        }

        @Test
        void rejectsMissingOrMalformedHeaders() {
            String ts = AN_APPROVED.headers().getFirst("X-Andina-Timestamp");
            String sig = AN_APPROVED.headers().getFirst("X-Andina-Signature");
            String[][] cases = {
                    {null, sig}, {ts, null}, {"yesterday", sig}, {ts, "%%%not-base64%%%"}, {ts, ""}};
            for (String[] c : cases) {
                HttpHeaders headers = new HttpHeaders();
                if (c[0] != null) {
                    headers.add("X-Andina-Timestamp", c[0]);
                }
                if (c[1] != null) {
                    headers.add("X-Andina-Signature", c[1]);
                }
                assertThatThrownBy(() -> adapter.verifySignature(AN_APPROVED.body(), headers, AN_APPROVED.signedAt()))
                        .as("timestamp=%s signature=%s", c[0], c[1])
                        .isInstanceOf(SignatureVerificationException.class);
            }
        }
    }

    @Nested
    class Mapping {

        @Test
        void approvedTransactionBecomesCaptured() {
            PremiumPaymentData data = adapter.toCanonical(AN_APPROVED.body());

            assertThat(data.status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(data.amount()).isEqualTo(new Money("185000.00", "COP"));
            assertThat(data.paymentKey()).isEqualTo("ANDINA:TRX-CO-5521907");
            assertThat(data.idempotencyKey()).isEqualTo("ANDINA:TRX-CO-5521907:APROBADA");
            assertThat(data.providerEventId()).isEqualTo("ntf-8f1c2a77");
            assertThat(data.statusChangedAt()).isEqualTo(Instant.parse("2026-11-28T19:03:09Z"));
            assertThat(data.paymentMethodType()).isEqualTo(PaymentMethodType.BANK_TRANSFER);
            assertThat(data.policy().number()).isEqualTo("POL-CO-000187342");
            assertThat(data.country()).isEqualTo("CO");
        }

        @Test
        void retryWithNewNotificationIdIsTheSameBusinessEvent() {
            PremiumPaymentData original = adapter.toCanonical(AN_APPROVED.body());
            PremiumPaymentData retry = adapter.toCanonical(AN_APPROVED_RETRY.body());

            assertThat(retry.providerEventId()).isNotEqualTo(original.providerEventId());
            assertThat(retry.idempotencyKey()).isEqualTo(original.idempotencyKey());
        }

        @Test
        void eachStateOfOneTransactionIsADistinctBusinessEventOfTheSamePayment() {
            PremiumPaymentData pending = adapter.toCanonical(AN_PENDING.body());
            PremiumPaymentData approved = adapter.toCanonical(AN_APPROVED.body());
            PremiumPaymentData reversed = adapter.toCanonical(AN_REVERSED.body());

            assertThat(pending.status()).isEqualTo(PaymentStatus.PENDING);
            assertThat(reversed.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(reversed.reason()).isEqualTo("SOLICITUD_BANCO");
            assertThat(java.util.Set.of(pending.idempotencyKey(), approved.idempotencyKey(), reversed.idempotencyKey()))
                    .hasSize(3);
            assertThat(java.util.List.of(pending.paymentKey(), approved.paymentKey(), reversed.paymentKey()))
                    .containsOnly("ANDINA:TRX-CO-5521907");
            // Ordering is resolved downstream on statusChangedAt + status precedence, not on arrival.
            assertThat(reversed.statusChangedAt()).isAfter(approved.statusChangedAt());
            assertThat(PaymentStatus.REFUNDED.rank()).isGreaterThan(PaymentStatus.CAPTURED.rank());
        }

        @Test
        void referenceAsTypedByThePayerSinceApi23IsNormalized() {
            // The two reference shapes seen in INC-0412.
            PremiumPaymentData digits = adapter.toCanonical(replace(AN_APPROVED.body(), "\"POL-CO-000187342\"", "\"000215560\""));
            PremiumPaymentData lower = adapter.toCanonical(replace(AN_APPROVED.body(), "\"POL-CO-000187342\"", "\"pol-co-000198004\""));

            assertThat(digits.policy().number()).isEqualTo("POL-CO-000215560");
            assertThat(digits.policy().referenceStatus()).isEqualTo(ReferenceStatus.NORMALIZED);
            assertThat(digits.policy().reference()).isEqualTo("000215560");
            assertThat(lower.policy().number()).isEqualTo("POL-CO-000198004");
        }

        @Test
        void unusableReferenceIsStillAcceptedButFlaggedInvalid() {
            PremiumPaymentData data = adapter.toCanonical(replace(AN_APPROVED.body(), "\"POL-CO-000187342\"", "\"poliza de mi mama\""));

            assertThat(data.policy().number()).isNull();
            assertThat(data.policy().referenceStatus()).isEqualTo(ReferenceStatus.INVALID);
        }

        @Test
        void unknownFieldsAddedByTheProviderAreIgnored() {
            PremiumPaymentData data = adapter.toCanonical(replace(AN_APPROVED.body(), "\"medio_pago\": \"PSE\"",
                    "\"medio_pago\": \"PSE\", \"canal\": \"APP\""));

            assertThat(data.status()).isEqualTo(PaymentStatus.CAPTURED);
        }

        @Test
        void unknownStateIsRejectedNotDefaultedToPending() {
            assertThatThrownBy(() -> adapter.toCanonical(replace(AN_APPROVED.body(), "\"APROBADA\"", "\"EN_DISPUTA\"")))
                    .isInstanceOf(UnsupportedNotificationException.class);
        }

        @Test
        void amountWithMoreThanTwoDecimalsOrNotNumericIsMalformed() {
            assertThatThrownBy(() -> adapter.toCanonical(replace(AN_APPROVED.body(), "\"185000.00\"", "\"185000.005\"")))
                    .isInstanceOf(MalformedNotificationException.class);
            assertThatThrownBy(() -> adapter.toCanonical(replace(AN_APPROVED.body(), "\"185000.00\"", "\"185.000,00\"")))
                    .isInstanceOf(MalformedNotificationException.class);
        }

        @Test
        void missingTransactionIdIsMalformed() {
            assertThatThrownBy(() -> adapter.toCanonical(replace(AN_APPROVED.body(), "\"id\": \"TRX-CO-5521907\",", "")))
                    .isInstanceOf(MalformedNotificationException.class)
                    .hasMessageNotContaining("Laura");
        }
    }
}
