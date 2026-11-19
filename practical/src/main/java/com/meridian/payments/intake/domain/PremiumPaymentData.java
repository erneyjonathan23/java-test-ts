package com.meridian.payments.intake.domain;

import java.time.Instant;

/**
 * Payload of the canonical event. Provider independent and free of payer personal data.
 * Contract: docs/api/events/premium-payment.schema.json
 */
public record PremiumPaymentData(
        String schemaVersion,
        String idempotencyKey,
        String paymentKey,
        String country,
        String provider,
        String providerTransactionId,
        String providerEventId,
        String providerEventType,
        PaymentStatus status,
        Instant statusChangedAt,
        Money amount,
        Money amountRefunded,
        PolicyReference policy,
        PaymentMethodType paymentMethodType,
        String reason) {

    public static final String SCHEMA_VERSION = "1.0";
}
