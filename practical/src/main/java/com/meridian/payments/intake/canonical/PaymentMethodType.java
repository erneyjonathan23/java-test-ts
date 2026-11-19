package com.meridian.payments.intake.canonical;

/** Payment method family. Never carries tokens or card data. */
public enum PaymentMethodType {
    CARD, BANK_TRANSFER, CASH, OTHER
}
