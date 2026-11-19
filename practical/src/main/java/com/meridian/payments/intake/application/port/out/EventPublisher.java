package com.meridian.payments.intake.application.port.out;

import com.meridian.payments.intake.domain.PremiumPaymentEvent;

/**
 * Publishes canonical events durably; returns only when the broker has the message.
 * Production: Service Bus topic, managed identity, messageId = idempotencyKey.
 */
public interface EventPublisher {

    /** @throws PublishException if the event could not be durably published (the caller answers 503). */
    void publish(PremiumPaymentEvent event);
}
