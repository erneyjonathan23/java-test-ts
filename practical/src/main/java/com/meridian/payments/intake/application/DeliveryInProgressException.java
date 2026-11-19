package com.meridian.payments.intake.application;

/**
 * Another delivery of the same business event is being published right now. Not accepted: the provider retries
 * and will then get {@code 200} (published) or publish it itself (if the other delivery failed).
 */
public class DeliveryInProgressException extends RuntimeException {

    public DeliveryInProgressException(String idempotencyKey) {
        super("delivery in progress for " + idempotencyKey);
    }
}
