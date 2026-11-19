package com.meridian.payments.intake.provider;

/** Authentic, well-formed request with an event type, state or currency this version does not know. */
public class UnsupportedNotificationException extends RuntimeException {

    public UnsupportedNotificationException(String message) {
        super(message);
    }
}
