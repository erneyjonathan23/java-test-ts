package com.meridian.payments.intake.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** CloudEvents 1.0 envelope around {@link PremiumPaymentData}. JSON names live in the messaging adapter. */
public record PremiumPaymentEvent(
        String specVersion,
        String id,
        String source,
        String type,
        Instant time,
        String dataContentType,
        String dataSchema,
        String subject,
        String correlationId,
        PremiumPaymentData data) {

    public static final String TYPE = "com.meridian.payments.premium-payment.status-changed";
    public static final String DATA_SCHEMA_BASE = "https://schemas.meridian.example/payments/premium-payment/";

    /** Wraps the payload; the id derives from the idempotency key, so a republished event keeps its id. */
    public static PremiumPaymentEvent wrap(PremiumPaymentData data, String providerId, Instant acceptedAt,
                                           String correlationId) {
        String id = UUID.nameUUIDFromBytes(
                ("premium-payment:" + data.idempotencyKey()).getBytes(StandardCharsets.UTF_8)).toString();
        return new PremiumPaymentEvent(
                "1.0",
                id,
                "/payments-intake/" + providerId,
                TYPE,
                acceptedAt,
                "application/json",
                DATA_SCHEMA_BASE + data.schemaVersion(),
                data.paymentKey(),
                correlationId,
                data);
    }
}
