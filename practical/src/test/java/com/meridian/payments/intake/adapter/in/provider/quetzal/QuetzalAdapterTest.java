package com.meridian.payments.intake.adapter.in.provider.quetzal;

import static com.meridian.payments.intake.TestFixtures.QUETZAL_TEST_SECRET;
import static com.meridian.payments.intake.TestFixtures.QZ_FAILED;
import static com.meridian.payments.intake.TestFixtures.QZ_REFUNDED;
import static com.meridian.payments.intake.TestFixtures.QZ_SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

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
import com.meridian.payments.intake.domain.PaymentMethodType;
import com.meridian.payments.intake.domain.PaymentStatus;
import com.meridian.payments.intake.domain.PolicyReferenceNormalizer;
import com.meridian.payments.intake.domain.PremiumPaymentData;
import com.meridian.payments.intake.domain.ReferenceStatus;
import com.meridian.payments.intake.domain.UnsupportedNotificationException;

class QuetzalAdapterTest {

    private final QuetzalAdapter adapter = adapterWithSecrets(QUETZAL_TEST_SECRET);

    private static QuetzalAdapter adapterWithSecrets(String... secrets) {
        return new QuetzalAdapter(TestFixtures.secrets(secrets), new ObjectMapper(), new PolicyReferenceNormalizer());
    }

    @Nested
    class Signature {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.meridian.payments.intake.TestFixtures#allQuetzal")
        void acceptsEveryPublishedTestVector(Vector vector) {
            assertThatCode(() -> adapter.verifySignature(vector.body(), vector.headers(), vector.signedAt()))
                    .doesNotThrowAnyException();
        }

        @Test
        void rejectsABodyChangedByOneByte() {
            byte[] tampered = Arrays.copyOf(QZ_SUCCEEDED.body(), QZ_SUCCEEDED.body().length);
            tampered[tampered.length - 2] = (byte) ' ';

            assertThatThrownBy(() -> adapter.verifySignature(tampered, QZ_SUCCEEDED.headers(), QZ_SUCCEEDED.signedAt()))
                    .isInstanceOf(SignatureVerificationException.class);
        }

        @Test
        void rejectsAReSerializedBody() {
            // Same JSON content, different bytes: the signature is over the raw bytes, not the meaning.
            byte[] compact = new String(QZ_SUCCEEDED.body(), StandardCharsets.UTF_8)
                    .replace("\n", "").replace("  ", "").getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> adapter.verifySignature(compact, QZ_SUCCEEDED.headers(), QZ_SUCCEEDED.signedAt()))
                    .isInstanceOf(SignatureVerificationException.class);
        }

        @Test
        void rejectsTheWrongSecret() {
            QuetzalAdapter other = adapterWithSecrets("qz_some_other_secret");

            assertThatThrownBy(() -> other.verifySignature(QZ_SUCCEEDED.body(), QZ_SUCCEEDED.headers(), QZ_SUCCEEDED.signedAt()))
                    .isInstanceOf(SignatureVerificationException.class);
        }

        @Test
        void acceptsTimestampsExactlyAtTheTolerance() {
            Instant signedAt = QZ_SUCCEEDED.signedAt();
            assertThatCode(() -> adapter.verifySignature(QZ_SUCCEEDED.body(), QZ_SUCCEEDED.headers(), signedAt.plusSeconds(300)))
                    .doesNotThrowAnyException();
            assertThatCode(() -> adapter.verifySignature(QZ_SUCCEEDED.body(), QZ_SUCCEEDED.headers(), signedAt.minusSeconds(300)))
                    .doesNotThrowAnyException();
        }

        @Test
        void rejectsReplaysOutsideTheFiveMinuteWindow() {
            Instant signedAt = QZ_SUCCEEDED.signedAt();
            assertThatThrownBy(() -> adapter.verifySignature(QZ_SUCCEEDED.body(), QZ_SUCCEEDED.headers(), signedAt.plusSeconds(301)))
                    .isInstanceOf(SignatureVerificationException.class)
                    .hasMessageContaining("tolerance");
            assertThatThrownBy(() -> adapter.verifySignature(QZ_SUCCEEDED.body(), QZ_SUCCEEDED.headers(), signedAt.minusSeconds(301)))
                    .isInstanceOf(SignatureVerificationException.class);
        }

        @Test
        void acceptsTwoSignaturesDuringSecretRotationWhenAnyMatches() {
            String valid = QZ_SUCCEEDED.headers().getFirst("Quetzal-Signature");
            HttpHeaders rotation = new HttpHeaders();
            rotation.add("Quetzal-Signature", valid.replace(",v1=", ",v1=" + "0".repeat(64) + ",v1="));

            assertThatCode(() -> adapter.verifySignature(QZ_SUCCEEDED.body(), rotation, QZ_SUCCEEDED.signedAt()))
                    .doesNotThrowAnyException();
        }

        @Test
        void acceptsTheNextSecretDuringOurOwnRotation() {
            QuetzalAdapter rotating = adapterWithSecrets("qz_new_secret_not_yet_used", QUETZAL_TEST_SECRET);

            assertThatCode(() -> rotating.verifySignature(QZ_SUCCEEDED.body(), QZ_SUCCEEDED.headers(), QZ_SUCCEEDED.signedAt()))
                    .doesNotThrowAnyException();
        }

        @Test
        void rejectsMissingOrMalformedHeaders() {
            Instant now = QZ_SUCCEEDED.signedAt();
            for (String header : new String[] {null, "", "v1=abc", "t=1795888803", "t=abc,v1=00",
                    "t=1795888803,v1=not-hex", "garbage", "t=1795888803,t=1795888803,v1=00"}) {
                HttpHeaders headers = new HttpHeaders();
                if (header != null) {
                    headers.add("Quetzal-Signature", header);
                }
                assertThatThrownBy(() -> adapter.verifySignature(QZ_SUCCEEDED.body(), headers, now))
                        .as("header %s", header)
                        .isInstanceOf(SignatureVerificationException.class);
            }
        }

        @Test
        void signedTimestampIsPartOfTheSignature() {
            // Re-using a valid v1 with a fresh t (classic replay attempt) must fail.
            String v1 = QZ_SUCCEEDED.headers().getFirst("Quetzal-Signature").split(",")[1];
            HttpHeaders replay = new HttpHeaders();
            replay.add("Quetzal-Signature", "t=1795888900," + v1);

            assertThatThrownBy(() -> adapter.verifySignature(QZ_SUCCEEDED.body(), replay, Instant.ofEpochSecond(1795888900)))
                    .isInstanceOf(SignatureVerificationException.class);
        }
    }

    @Nested
    class Mapping {

        @Test
        void succeededChargeBecomesCapturedWithMinorUnitsConverted() {
            PremiumPaymentData data = adapter.toCanonical(QZ_SUCCEEDED.body());

            assertThat(data.status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(data.amount()).isEqualTo(new Money("1250.00", "MXN"));
            assertThat(data.idempotencyKey()).isEqualTo("QUETZAL:evt_01JDQ3K8M2X7");
            assertThat(data.paymentKey()).isEqualTo("QUETZAL:ch_7Hk2Lm9QpZ");
            assertThat(data.providerEventId()).isEqualTo("evt_01JDQ3K8M2X7");
            assertThat(data.statusChangedAt()).isEqualTo(Instant.ofEpochSecond(1795888796)); // paid_at
            assertThat(data.country()).isEqualTo("MX");
            assertThat(data.policy().number()).isEqualTo("POL-MX-000412785");
            assertThat(data.policy().referenceStatus()).isEqualTo(ReferenceStatus.VALID);
            assertThat(data.paymentMethodType()).isEqualTo(PaymentMethodType.CARD);
            assertThat(data.amountRefunded()).isNull();
        }

        @Test
        void failedChargeKeepsTheFailureCodeAndUsesTheEventTime() {
            PremiumPaymentData data = adapter.toCanonical(QZ_FAILED.body());

            assertThat(data.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(data.reason()).isEqualTo("insufficient_funds");
            assertThat(data.statusChangedAt()).isEqualTo(Instant.ofEpochSecond(1795889400)); // created; paid_at is null
            assertThat(data.amount()).isEqualTo(new Money("980.00", "MXN"));
        }

        @Test
        void refundBelongsToTheSamePaymentButIsADifferentBusinessEvent() {
            PremiumPaymentData captured = adapter.toCanonical(QZ_SUCCEEDED.body());
            PremiumPaymentData refunded = adapter.toCanonical(QZ_REFUNDED.body());

            assertThat(refunded.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(refunded.paymentKey()).isEqualTo(captured.paymentKey());
            assertThat(refunded.idempotencyKey()).isNotEqualTo(captured.idempotencyKey());
            assertThat(refunded.amountRefunded()).isEqualTo(new Money("1250.00", "MXN"));
            assertThat(refunded.statusChangedAt()).isEqualTo(Instant.ofEpochSecond(1795975195)); // refunded_at
        }

        @Test
        void retriedDeliveryHasTheSameIdempotencyKey() {
            assertThat(adapter.toCanonical(QZ_SUCCEEDED.body()).idempotencyKey())
                    .isEqualTo(adapter.toCanonical(QZ_SUCCEEDED.body()).idempotencyKey());
        }

        @Test
        void unknownEventTypeIsRejectedNotDefaulted() {
            byte[] body = new String(QZ_SUCCEEDED.body(), StandardCharsets.UTF_8)
                    .replace("charge.succeeded", "charge.disputed").getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> adapter.toCanonical(body)).isInstanceOf(UnsupportedNotificationException.class);
        }

        @Test
        void fractionalMinorUnitAmountIsMalformed() {
            byte[] body = new String(QZ_SUCCEEDED.body(), StandardCharsets.UTF_8)
                    .replace("\"amount\": 125000", "\"amount\": 1250.00").getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> adapter.toCanonical(body)).isInstanceOf(MalformedNotificationException.class);
        }

        @Test
        void duplicateJsonKeysAreRejected() {
            byte[] body = "{\"id\":\"evt_1\",\"id\":\"evt_2\",\"type\":\"charge.succeeded\"}".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> adapter.toCanonical(body)).isInstanceOf(MalformedNotificationException.class);
        }
    }
}
