package com.meridian.payments.intake.provider.support;

public class SecretUnavailableException extends RuntimeException {

    public SecretUnavailableException(String message) {
        super(message);
    }
}
