package com.meridian.payments.intake.domain;

/** Canonical lifecycle. The rank orders states: a payment never moves to a lower rank. */
public enum PaymentStatus {
    PENDING(0),
    CAPTURED(1),
    FAILED(1),
    REFUNDED(2);

    private final int rank;

    PaymentStatus(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
