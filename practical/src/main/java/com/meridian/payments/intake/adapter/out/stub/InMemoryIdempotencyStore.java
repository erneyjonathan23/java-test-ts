package com.meridian.payments.intake.adapter.out.stub;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.meridian.payments.intake.domain.PremiumPaymentEvent;
import com.meridian.payments.intake.application.port.out.IdempotencyStore;

/** STUB of the Azure SQL idempotency tables. Same semantics, kept in memory. */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    public record Attempt(String provider, String providerEventId, String idempotencyKey,
                          AttemptOutcome outcome, String correlationId) {
    }

    private record Entry(PremiumPaymentEvent firstEvent, boolean published) {
    }

    private final Map<String, Entry> events = new ConcurrentHashMap<>();
    private final List<Attempt> attempts = new CopyOnWriteArrayList<>();

    @Override
    public ClaimResult claim(PremiumPaymentEvent event) {
        AtomicReference<ClaimResult> result = new AtomicReference<>();
        events.compute(event.data().idempotencyKey(), (key, existing) -> {
            if (existing == null) {
                result.set(ClaimResult.NEW);
                return new Entry(event, false);
            }
            result.set(existing.published() ? ClaimResult.ALREADY_PUBLISHED : ClaimResult.CLAIMED_NOT_PUBLISHED);
            return existing;
        });
        return result.get();
    }

    @Override
    public void markPublished(String idempotencyKey) {
        events.computeIfPresent(idempotencyKey, (key, existing) -> new Entry(existing.firstEvent(), true));
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
