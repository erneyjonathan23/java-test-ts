package com.meridian.payments.intake.domain;

import java.time.Instant;

/** Canonical event payload: provider independent, no payer personal data. */
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
