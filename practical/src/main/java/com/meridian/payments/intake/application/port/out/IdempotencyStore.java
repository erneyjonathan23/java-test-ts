package com.meridian.payments.intake.application.port.out;

import java.time.Instant;

import com.meridian.payments.intake.domain.PremiumPaymentEvent;

/**
 * Remembers which business events have already been published, and leases the ones being published.
 * <p>
 * Production implementation (not part of this deliverable): Azure SQL tables {@code inbound_event}
 * (primary key {@code idempotency_key}, lease column {@code claimed_until}) and {@code inbound_attempt},
 * see db/migrations. {@link #claim} is one {@code INSERT}, or on a primary-key violation one conditional
 * {@code UPDATE … SET claimed_until = @now + lease WHERE … AND published = 0 AND (claimed_until IS NULL OR claimed_until < @now)}.
 */
public interface IdempotencyStore {

    enum ClaimResult {
        /** First time this business event is seen; the caller now holds the lease and must publish. */
        NEW,
        /** Seen before but never published (crash, broker outage) and not leased: the caller holds the lease now. */
        CLAIMED_NOT_PUBLISHED,
        /** Another delivery of the same event holds a live lease and is publishing it right now. */
        IN_FLIGHT,
        /** Already published: acknowledge without publishing again. */
        ALREADY_PUBLISHED
    }

    enum AttemptOutcome { PUBLISHED, DUPLICATE }

    /**
     * Registers the business event (keeping the canonical event for replay) and takes a short lease on it,
     * so two concurrent deliveries of the same event cannot both publish it.
     *
     * @throws StoreUnavailableException if the store cannot be reached (the caller answers 503)
     */
    ClaimResult claim(PremiumPaymentEvent event, Instant now);

    void markPublished(String idempotencyKey);

    /** Gives the lease back after a failed publication, so the provider's retry is not blocked until it expires. */
    void release(String idempotencyKey);

    /** Records one delivery attempt so operations can find it by the provider's own id (FR7). */
    void recordAttempt(PremiumPaymentEvent event, AttemptOutcome outcome);
}
