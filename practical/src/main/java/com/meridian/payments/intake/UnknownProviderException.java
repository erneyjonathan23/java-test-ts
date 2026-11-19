package com.meridian.payments.intake;

public class UnknownProviderException extends RuntimeException {

    public UnknownProviderException(String providerId) {
        super("unknown provider " + providerId);
    }
}
