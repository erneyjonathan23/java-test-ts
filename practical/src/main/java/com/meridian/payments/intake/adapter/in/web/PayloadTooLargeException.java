package com.meridian.payments.intake.adapter.in.web;

public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException() {
        super("body too large");
    }
}
