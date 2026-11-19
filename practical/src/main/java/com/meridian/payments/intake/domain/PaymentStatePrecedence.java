package com.meridian.payments.intake.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;

/**
 * Resolves out-of-order and duplicate notifications on <b>state</b>, not on arrival (FR5, ADR-0003).
 * <p>
 * Rules, by {@link PaymentStatus#rank()} ({@code PENDING} &lt; {@code CAPTURED}/{@code FAILED} &lt; {@code REFUNDED}):
 * <ol>
 *   <li>a higher rank always wins; a lower rank is {@link Decision#SUPERSEDED} — a refunded payment can never
 *       go back to captured, whatever arrives late;</li>
 *   <li>same status: a larger refunded amount (successive partial refunds) is applied, anything else is a
 *       {@link Decision#DUPLICATE};</li>
 *   <li>same rank, different status ({@code CAPTURED} vs {@code FAILED}, an anomaly): the later state change wins.</li>
 * </ol>
 * The result does not depend on the order in which events arrive ({@link #fold}); the recorder uses this
 * function with an optimistic-concurrency update of {@code payment_state}, so duplicates and replays are safe.
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
        if (byRank != 0) {
            return byRank > 0 ? Decision.APPLY : Decision.SUPERSEDED;
        }
        if (incoming.status() == current.status()) {
            return refunded(incoming).compareTo(refunded(current)) > 0 ? Decision.APPLY : Decision.DUPLICATE;
        }
        // Same rank, different status (CAPTURED vs FAILED): the later state change at the provider wins.
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
