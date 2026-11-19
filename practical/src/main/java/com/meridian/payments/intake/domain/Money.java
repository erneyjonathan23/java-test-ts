package com.meridian.payments.intake.domain;

/** Amount as a decimal string in the major unit ("1250.00" MXN, "45990" CLP). Never a float. */
public record Money(String value, String currency) {
}
