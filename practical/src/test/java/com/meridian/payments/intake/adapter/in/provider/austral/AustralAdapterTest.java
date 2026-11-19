package com.meridian.payments.intake.adapter.in.provider.austral;

import static com.meridian.payments.intake.TestFixtures.AUSTRAL_TEST_SECRET;
import static com.meridian.payments.intake.TestFixtures.AU_CONFIRMED;
import static com.meridian.payments.intake.TestFixtures.AU_REVERSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.intake.TestFixtures.Vector;
import com.meridian.payments.intake.TestFixtures;
import com.meridian.payments.intake.application.SignatureVerificationException;
import com.meridian.payments.intake.domain.MalformedNotificationException;
import com.meridian.payments.intake.domain.Money;
import com.meridian.payments.intake.domain.PaymentStatus;
import com.meridian.payments.intake.domain.PolicyReferenceNormalizer;
import com.meridian.payments.intake.domain.PremiumPaymentData;
import com.meridian.payments.intake.domain.UnsupportedNotificationException;

/** Austral Pagos (Chile): the behaviours PR-214 got wrong are tested here explicitly. */
class AustralAdapterTest {

    private final AustralAdapter adapter = new AustralAdapter(
            TestFixtures.secrets(AUSTRAL_TEST_SECRET), new ObjectMapper(), new PolicyReferenceNormalizer());

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
        @MethodSource("com.meridian.payments.intake.TestFixtures#allAustral")
        void acceptsEveryPublishedTestVector(Vector vector) {
            assertThatCode(() -> adapter.verifySignature(vector.body(), vector.headers(), Instant.now()))
                    .doesNotThrowAnyException();
        }

        @Test
        void missingSignatureIsRejectedNeverSkipped() {
            // PR-214 skipped verification when the header was absent.
            assertThatThrownBy(() -> adapter.verifySignature(AU_CONFIRMED.body(), new HttpHeaders(), Instant.now()))
                    .isInstanceOf(SignatureVerificationException.class);
        }

        @Test
        void rejectsATamperedBodyAndNonHexSignatures() {
            byte[] tampered = replace(AU_CONFIRMED.body(), "\"45990\"", "\"45991\"");
            assertThatThrownBy(() -> adapter.verifySignature(tampered, AU_CONFIRMED.headers(), Instant.now()))
                    .isInstanceOf(SignatureVerificationException.class);

            HttpHeaders notHex = new HttpHeaders();
            notHex.add("X-Austral-Signature", "zz-not-hex");
            assertThatThrownBy(() -> adapter.verifySignature(AU_CONFIRMED.body(), notHex, Instant.now()))
                    .isInstanceOf(SignatureVerificationException.class);
        }

        @Test
        void signatureOfAnotherEventIsRejected() {
            assertThatThrownBy(() -> adapter.verifySignature(AU_CONFIRMED.body(), AU_REVERSED.headers(), Instant.now()))
                    .isInstanceOf(SignatureVerificationException.class);
        }
    }

    @Nested
    class Mapping {

        @Test
        void confirmedPaymentIsCapturedWithClpWithoutDecimals() {
            PremiumPaymentData data = adapter.toCanonical(AU_CONFIRMED.body());

            assertThat(data.status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(data.amount()).isEqualTo(new Money("45990", "CLP"));
            assertThat(data.idempotencyKey()).isEqualTo("AUSTRAL:aus_ev_93f0c1d2");
            assertThat(data.paymentKey()).isEqualTo("AUSTRAL:AUS-P-77120");
            assertThat(data.country()).isEqualTo("CL");
            assertThat(data.statusChangedAt()).isEqualTo(Instant.parse("2026-11-28T17:05:40Z"));
            assertThat(data.policy().number()).isEqualTo("POL-CL-000093311");
        }

        @Test
        void reversalBelongsToTheSamePaymentAndOutranksTheCapture() {
            PremiumPaymentData confirmed = adapter.toCanonical(AU_CONFIRMED.body());
            PremiumPaymentData reversed = adapter.toCanonical(AU_REVERSED.body());

            assertThat(reversed.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(reversed.paymentKey()).isEqualTo(confirmed.paymentKey());
            assertThat(reversed.idempotencyKey()).isNotEqualTo(confirmed.idempotencyKey());
            assertThat(reversed.status().rank()).isGreaterThan(confirmed.status().rank());
        }

        @Test
        void servesChile() {
            assertThat(adapter.country()).isEqualTo("CL");
        }

        @Test
        void clpWithDecimalsIsRejectedNotRounded() {
            assertThatThrownBy(() -> adapter.toCanonical(replace(AU_CONFIRMED.body(), "\"45990\"", "\"45990.50\"")))
                    .isInstanceOf(MalformedNotificationException.class);
        }

        @Test
        void unknownEventTypeIsRejectedNotDefaultedToPending() {
            assertThatThrownBy(() -> adapter.toCanonical(replace(AU_CONFIRMED.body(), "PAYMENT_CONFIRMED", "PAYMENT_DISPUTED")))
                    .isInstanceOf(UnsupportedNotificationException.class);
        }
    }
}
