package com.meridian.payments.intake.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import com.meridian.payments.intake.domain.MalformedNotificationException;
import com.meridian.payments.intake.domain.Money;
import com.meridian.payments.intake.domain.UnsupportedNotificationException;

/** Money conversions with explicit ISO 4217 exponents. No floating point anywhere. */
public final class Amounts {

    private static final Map<String, Integer> EXPONENTS = Map.of(
            "MXN", 2,
            "COP", 2,
            "CLP", 0);

    private Amounts() {
    }

    public static int exponent(String currency) {
        Integer exponent = EXPONENTS.get(currency);
        if (exponent == null) {
            throw new UnsupportedNotificationException("unsupported currency " + currency);
        }
        return exponent;
    }

    /** From an amount in the currency's minor unit (e.g. centavos). */
    public static Money fromMinorUnits(long minor, String currency) {
        if (minor < 0) {
            throw new MalformedNotificationException("negative amount");
        }
        return new Money(BigDecimal.valueOf(minor).movePointLeft(exponent(currency)).toPlainString(), currency);
    }

    /** From a decimal amount in the major unit; rejects more decimals than the currency allows. */
    public static Money fromMajorUnits(BigDecimal major, String currency) {
        if (major.signum() < 0) {
            throw new MalformedNotificationException("negative amount");
        }
        try {
            return new Money(major.setScale(exponent(currency), RoundingMode.UNNECESSARY).toPlainString(), currency);
        } catch (ArithmeticException e) {
            throw new MalformedNotificationException("amount has more decimals than " + currency + " allows");
        }
    }
}
