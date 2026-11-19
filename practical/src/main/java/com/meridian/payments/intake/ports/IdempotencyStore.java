package com.meridian.payments.intake.ports;

import com.meridian.payments.intake.canonical.PremiumPaymentEvent;

/**
 * Remembers which business events have already been published.
 * <p>
 * Production implementation (not part of this deliverable): Azure SQL tables {@code inbound_event}
 * (primary key {@code idempotency_key}) and {@code inbound_attempt}, see SOLUTION_DESIGN.md section 8.
 * {@link #claim} is a single {@code INSERT} that falls back to reading the existing row on a
 * primary-key violation.
 */
public interface IdempotencyStore {

    enum ClaimResult {
        /** First time this business event is seen. */
        NEW,
        /** Seen before but never confirmed as published (crash or broker outage): publish again. */
        CLAIMED_NOT_PUBLISHED,
        /** Already published: acknowledge without publishing again. */
        ALREADY_PUBLISHED
    }

    enum AttemptOutcome { PUBLISHED, DUPLICATE }

    /**
     * Registers the business event (and keeps the canonical event for replay) if it is new.
     *
     * @throws StoreUnavailableException if the store cannot be reached (the caller answers 503)
     */
    ClaimResult claim(PremiumPaymentEvent event);

    void markPublished(String idempotencyKey);

    /** Records one delivery attempt so operations can find it by the provider's own id (FR7). */
    void recordAttempt(PremiumPaymentEvent event, AttemptOutcome outcome);
}
