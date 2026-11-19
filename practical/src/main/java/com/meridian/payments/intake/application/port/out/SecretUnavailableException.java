package com.meridian.payments.intake.application.port.out;

public class SecretUnavailableException extends RuntimeException {

    public SecretUnavailableException(String message) {
        super(message);
    }
}
