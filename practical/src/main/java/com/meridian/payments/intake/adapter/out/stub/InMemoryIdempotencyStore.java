package com.meridian.payments.intake.adapter.out.stub;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.meridian.payments.intake.application.port.out.IdempotencyStore;
import com.meridian.payments.intake.domain.PremiumPaymentEvent;

/** STUB of the Azure SQL idempotency tables. Same semantics (including the lease), kept in memory. */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    public record Attempt(String provider, String providerEventId, String idempotencyKey,
                          AttemptOutcome outcome, String correlationId) {
    }

    private record Entry(PremiumPaymentEvent firstEvent, boolean published, Instant claimedUntil) {
    }

    private final Map<String, Entry> events = new ConcurrentHashMap<>();
    private final List<Attempt> attempts = new CopyOnWriteArrayList<>();
    private final Duration lease;

    /** @param lease longer than the publish timeout, so a live publication is never leased twice */
    public InMemoryIdempotencyStore(Duration lease) {
        this.lease = lease;
    }

    @Override
    public ClaimResult claim(PremiumPaymentEvent event, Instant now) {
        AtomicReference<ClaimResult> result = new AtomicReference<>();
        events.compute(event.data().idempotencyKey(), (key, existing) -> {
            if (existing == null) {
                result.set(ClaimResult.NEW);
                return new Entry(event, false, now.plus(lease));
            }
            if (existing.published()) {
                result.set(ClaimResult.ALREADY_PUBLISHED);
                return existing;
            }
            if (existing.claimedUntil() != null && existing.claimedUntil().isAfter(now)) {
                result.set(ClaimResult.IN_FLIGHT);
                return existing;
            }
            result.set(ClaimResult.CLAIMED_NOT_PUBLISHED);
            return new Entry(existing.firstEvent(), false, now.plus(lease));
        });
        return result.get();
    }

    @Override
    public void markPublished(String idempotencyKey) {
        events.computeIfPresent(idempotencyKey, (key, existing) -> new Entry(existing.firstEvent(), true, null));
    }

    @Override
    public void release(String idempotencyKey) {
        events.computeIfPresent(idempotencyKey, (key, existing) ->
                existing.published() ? existing : new Entry(existing.firstEvent(), false, null));
    }

    @Override
    public void recordAttempt(PremiumPaymentEvent event, AttemptOutcome outcome) {
        attempts.add(new Attempt(event.data().provider(), event.data().providerEventId(),
                event.data().idempotencyKey(), outcome, event.correlationId()));
    }

    public List<Attempt> attempts() {
        return List.copyOf(attempts);
    }

    public void clear() {
        events.clear();
        attempts.clear();
    }
}
