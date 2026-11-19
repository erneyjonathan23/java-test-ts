package com.meridian.payments.intake.application.port.out;

import java.time.Instant;

import com.meridian.payments.intake.domain.PremiumPaymentEvent;

/**
 * Tracks published events and leases the ones being published.
 * Production: Azure SQL tables from db/migrations (INSERT, then conditional UPDATE of claimed_until).
 */
public interface IdempotencyStore {

    enum ClaimResult {
        /** First time seen: the caller holds the lease and must publish. */
        NEW,
        /** Seen but never published (crash, outage): the caller holds the lease now. */
        CLAIMED_NOT_PUBLISHED,
        /** Another delivery of the same event holds a live lease and is publishing it right now. */
        IN_FLIGHT,
        /** Already published: acknowledge without publishing again. */
        ALREADY_PUBLISHED
    }

    enum AttemptOutcome { PUBLISHED, DUPLICATE }

    /**
     * Registers the event and takes a short lease, so concurrent deliveries publish it once.
     *
     * @throws StoreUnavailableException if the store is down (caller answers 503)
     */
    ClaimResult claim(PremiumPaymentEvent event, Instant now);

    void markPublished(String idempotencyKey);

    /** Frees the lease after a failed publish so the retry is not blocked. */
    void release(String idempotencyKey);

    /** Records one delivery attempt so operations can find it by the provider's own id (FR7). */
    void recordAttempt(PremiumPaymentEvent event, AttemptOutcome outcome);
}
