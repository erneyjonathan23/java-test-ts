package com.meridian.payments.intake.application;

public class UnknownProviderException extends RuntimeException {

    public UnknownProviderException(String providerId) {
        super("unknown provider " + providerId);
    }
}
