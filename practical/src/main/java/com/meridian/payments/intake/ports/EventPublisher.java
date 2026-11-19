package com.meridian.payments.intake.ports;

import com.meridian.payments.intake.canonical.PremiumPaymentEvent;

/**
 * Durable publication of canonical events.
 * <p>
 * Production implementation (not part of this deliverable): Azure Service Bus topic
 * {@code premium-payment-events}, authenticated with the app's managed identity, with
 * {@code messageId = data.idempotencyKey}, {@code correlationId = correlationid},
 * application properties {@code country}, {@code provider}, {@code status}, {@code dataschema},
 * and a hard send timeout of 3 s so the provider always gets an answer before its own timeout.
 * The call returns only after the broker has acknowledged the message.
 */
public interface EventPublisher {

    /** @throws PublishException if the event could not be durably published (the caller answers 503). */
    void publish(PremiumPaymentEvent event);
}
