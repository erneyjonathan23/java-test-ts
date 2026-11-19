package com.meridian.payments.intake.provider;

/** Authentic request whose body cannot be understood. Messages name fields, never their values. */
public class MalformedNotificationException extends RuntimeException {

    public MalformedNotificationException(String message) {
        super(message);
    }
}
