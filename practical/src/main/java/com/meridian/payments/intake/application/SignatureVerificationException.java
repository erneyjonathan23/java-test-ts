package com.meridian.payments.intake.application;

/** The request is not authentic (or not fresh). The reason is for our logs only, never returned to the caller. */
public class SignatureVerificationException extends RuntimeException {

    public SignatureVerificationException(String reason) {
        super(reason);
    }
}
