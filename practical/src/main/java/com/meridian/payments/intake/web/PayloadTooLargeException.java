package com.meridian.payments.intake.web;

public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException() {
        super("body too large");
    }
}
