package com.meridian.payments.intake.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;

/**
 * Resolves out-of-order and duplicate notifications by state, not arrival (FR5).
 * Higher rank wins (PENDING < CAPTURED/FAILED < REFUNDED); same status applies only a larger refund;
 * CAPTURED vs FAILED: the later change wins. The final state does not depend on arrival order.
 */
public final class PaymentStatePrecedence {

    public enum Decision {
        /** The incoming event becomes the payment's current state. */
        APPLY,
        /** Same business fact as the current state: nothing to do. */
        DUPLICATE,
        /** Older or weaker than the current state: kept for audit, never written to Salesforce. */
        SUPERSEDED
    }

    /** The parts of a payment's state that ordering depends on. */
    public record Snapshot(PaymentStatus status, Instant statusChangedAt, Money amountRefunded) {

        public Snapshot {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(statusChangedAt, "statusChangedAt");
        }

        public static Snapshot of(PremiumPaymentData data) {
            return new Snapshot(data.status(), data.statusChangedAt(), data.amountRefunded());
        }
    }

    private PaymentStatePrecedence() {
    }

    public static Decision decide(Snapshot current, Snapshot incoming) {
        Objects.requireNonNull(incoming, "incoming");
        if (current == null) {
            return Decision.APPLY;
        }
        int byRank = Integer.compare(incoming.status().rank(), current.status().rank());
        if (byRank > 0) {
            return Decision.APPLY;
        }
        if (byRank < 0) {
            return Decision.SUPERSEDED;
        }
        if (incoming.status() == current.status()) {
            return refunded(incoming).compareTo(refunded(current)) > 0 ? Decision.APPLY : Decision.DUPLICATE;
        }
        // CAPTURED vs FAILED: the later change wins.
        return incoming.statusChangedAt().isAfter(current.statusChangedAt()) ? Decision.APPLY : Decision.SUPERSEDED;
    }

    /** Final state after applying every event, in whatever order they are given. */
    public static Snapshot fold(Collection<Snapshot> events) {
        Snapshot state = null;
        for (Snapshot event : events) {
            if (decide(state, event) == Decision.APPLY) {
                state = event;
            }
        }
        return state;
    }

    private static BigDecimal refunded(Snapshot snapshot) {
        return snapshot.amountRefunded() == null ? BigDecimal.ZERO : new BigDecimal(snapshot.amountRefunded().value());
    }
}
