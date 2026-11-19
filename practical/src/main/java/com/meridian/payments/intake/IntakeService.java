package com.meridian.payments.intake;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import com.meridian.payments.intake.canonical.PremiumPaymentData;
import com.meridian.payments.intake.canonical.PremiumPaymentEvent;
import com.meridian.payments.intake.ports.EventPublisher;
import com.meridian.payments.intake.ports.IdempotencyStore;
import com.meridian.payments.intake.ports.IdempotencyStore.AttemptOutcome;
import com.meridian.payments.intake.ports.IdempotencyStore.ClaimResult;
import com.meridian.payments.intake.provider.ProviderAdapter;

/**
 * Shared intake flow, identical for every provider:
 * verify signature → map to canonical → claim idempotency key → publish → acknowledge.
 * <p>
 * A notification is accepted (2xx) only after the event has been durably published (NFR3).
 * Every exception thrown from here means "not accepted" and is translated to a non-2xx status,
 * so the provider retries.
 */
@Service
public class IntakeService {

    public enum Outcome { PUBLISHED, DUPLICATE }

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    private final Map<String, ProviderAdapter> adapters;
    private final IdempotencyStore idempotencyStore;
    private final EventPublisher publisher;
    private final Clock clock;

    public IntakeService(List<ProviderAdapter> adapters, IdempotencyStore idempotencyStore,
                         EventPublisher publisher, Clock clock) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(ProviderAdapter::id, Function.identity()));
        this.idempotencyStore = idempotencyStore;
        this.publisher = publisher;
        this.clock = clock;
    }

    public Outcome receive(String providerId, byte[] rawBody, HttpHeaders headers, String correlationId) {
        ProviderAdapter adapter = adapters.get(providerId);
        if (adapter == null) {
            throw new UnknownProviderException(providerId);
        }
        Instant now = clock.instant();

        adapter.verifySignature(rawBody, headers, now);
        PremiumPaymentData data = adapter.toCanonical(rawBody);
        PremiumPaymentEvent event = PremiumPaymentEvent.wrap(data, adapter.id(), now, correlationId);

        ClaimResult claim = idempotencyStore.claim(event);
        if (claim == ClaimResult.ALREADY_PUBLISHED) {
            recordAttempt(event, AttemptOutcome.DUPLICATE);
            log.info("notification acknowledged as duplicate provider={} providerEventId={} idempotencyKey={}",
                    data.provider(), data.providerEventId(), data.idempotencyKey());
            return Outcome.DUPLICATE;
        }

        publisher.publish(event);

        // From here the event is durably published, so the notification is accepted even if the
        // bookkeeping below fails: the worst case is one extra publication on a later retry,
        // which the recorder absorbs (idempotent, precedence-guarded).
        try {
            idempotencyStore.markPublished(data.idempotencyKey());
        } catch (RuntimeException e) {
            log.warn("event published but not marked as published idempotencyKey={} error={}",
                    data.idempotencyKey(), e.getClass().getSimpleName());
        }
        recordAttempt(event, AttemptOutcome.PUBLISHED);

        log.info("notification accepted provider={} providerEventId={} idempotencyKey={} status={} referenceStatus={} claim={}",
                data.provider(), data.providerEventId(), data.idempotencyKey(), data.status(),
                data.policy().referenceStatus(), claim);
        return Outcome.PUBLISHED;
    }

    private void recordAttempt(PremiumPaymentEvent event, AttemptOutcome outcome) {
        try {
            idempotencyStore.recordAttempt(event, outcome);
        } catch (RuntimeException e) {
            // Losing an attempt record degrades FR7 lookups by a retry's id; it must not reject the notification.
            log.warn("could not record attempt idempotencyKey={} error={}",
                    event.data().idempotencyKey(), e.getClass().getSimpleName());
        }
    }
}
