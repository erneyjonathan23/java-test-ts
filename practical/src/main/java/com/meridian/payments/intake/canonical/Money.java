package com.meridian.payments.intake.canonical;

/**
 * Amount in the MAJOR unit as a decimal string with the currency's number of decimals
 * ("1250.00" MXN, "185000.00" COP, "45990" CLP). Never a floating point number.
 */
public record Money(String value, String currency) {
}
