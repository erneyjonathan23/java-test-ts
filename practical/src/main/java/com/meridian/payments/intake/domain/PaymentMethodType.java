package com.meridian.payments.intake.domain;

/** Payment method family. Never carries tokens or card data. */
public enum PaymentMethodType {
    CARD, BANK_TRANSFER, CASH, OTHER
}
