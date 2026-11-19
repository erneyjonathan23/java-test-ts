package com.meridian.payments.intake.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/** CloudEvents 1.0 envelope (structured JSON mode) around {@link PremiumPaymentData}. */
public record PremiumPaymentEvent(
        @JsonProperty("specversion") String specVersion,
        String id,
        String source,
        String type,
        Instant time,
        @JsonProperty("datacontenttype") String dataContentType,
        @JsonProperty("dataschema") String dataSchema,
        String subject,
        @JsonProperty("correlationid") String correlationId,
        PremiumPaymentData data) {

    public static final String TYPE = "com.meridian.payments.premium-payment.status-changed";
    public static final String DATA_SCHEMA_BASE = "https://schemas.meridian.example/payments/premium-payment/";

    /**
     * Wraps the payload. The event id is derived from the idempotency key, so the same business
     * event keeps the same id even if it has to be published again.
     */
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
