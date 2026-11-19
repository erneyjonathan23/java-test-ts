package com.meridian.payments.intake.application;

/** The same event is being published by a concurrent delivery; the provider should retry. */
public class DeliveryInProgressException extends RuntimeException {

    public DeliveryInProgressException(String idempotencyKey) {
        super("delivery in progress for " + idempotencyKey);
    }
}
