package com.meridian.payments.intake.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.meridian.payments.intake.application.port.out.EventPublisher;
import com.meridian.payments.intake.application.port.out.IdempotencyStore.AttemptOutcome;
import com.meridian.payments.intake.application.port.out.IdempotencyStore.ClaimResult;
import com.meridian.payments.intake.application.port.out.IdempotencyStore;
import com.meridian.payments.intake.application.port.out.IntakeMetrics.Outcome;
import com.meridian.payments.intake.application.port.out.IntakeMetrics.Rejection;
import com.meridian.payments.intake.application.port.out.IntakeMetrics;
import com.meridian.payments.intake.application.spi.ProviderAdapter;
import com.meridian.payments.intake.domain.MalformedNotificationException;
import com.meridian.payments.intake.domain.PremiumPaymentData;
import com.meridian.payments.intake.domain.PremiumPaymentEvent;
import com.meridian.payments.intake.domain.UnsupportedNotificationException;

/**
 * Use case: verify → map → claim → publish → acknowledge, the same for every provider.
 * Returns only after the event is durably published (NFR3); any exception means "not accepted".
 */
public class IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    private final Map<String, ProviderAdapter> adapters;
    private final IdempotencyStore idempotencyStore;
    private final EventPublisher publisher;
    private final IntakeMetrics metrics;
    private final Clock clock;

    public IntakeService(List<ProviderAdapter> adapters, IdempotencyStore idempotencyStore,
                         EventPublisher publisher, IntakeMetrics metrics, Clock clock) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(ProviderAdapter::id, Function.identity()));
        this.idempotencyStore = idempotencyStore;
        this.publisher = publisher;
        this.metrics = metrics;
        this.clock = clock;
    }

    public Outcome receive(String providerId, byte[] rawBody, RequestHeaders headers, String correlationId) {
        ProviderAdapter adapter = adapters.get(providerId);
        if (adapter == null) {
            throw new UnknownProviderException(providerId);
        }
        long started = System.nanoTime();
        try {
            Outcome outcome = process(adapter, rawBody, headers, correlationId);
            metrics.accepted(adapter.id(), adapter.country(), outcome, Duration.ofNanos(System.nanoTime() - started));
            return outcome;
        } catch (RuntimeException e) {
            metrics.rejected(adapter.id(), adapter.country(), classify(e));
            throw e;
        }
    }

    private Outcome process(ProviderAdapter adapter, byte[] rawBody, RequestHeaders headers, String correlationId) {
        Instant now = clock.instant();

        adapter.verifySignature(rawBody, headers, now);
        PremiumPaymentData data = adapter.toCanonical(rawBody);
        PremiumPaymentEvent event = PremiumPaymentEvent.wrap(data, adapter.id(), now, correlationId);

        ClaimResult claim = idempotencyStore.claim(event, now);
        if (claim == ClaimResult.IN_FLIGHT) {
            // Another delivery is publishing this event: let the provider retry.
            throw new DeliveryInProgressException(data.idempotencyKey());
        }
        if (claim == ClaimResult.ALREADY_PUBLISHED) {
            recordAttempt(event, AttemptOutcome.DUPLICATE);
            log.info("notification acknowledged as duplicate provider={} providerEventId={} idempotencyKey={}",
                    data.provider(), data.providerEventId(), data.idempotencyKey());
            return Outcome.DUPLICATE;
        }

        try {
            publisher.publish(event);
        } catch (RuntimeException e) {
            releaseQuietly(data.idempotencyKey());
            throw e;
        }

        // Published = accepted. If bookkeeping fails, the worst case is a harmless duplicate later.
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

    private void releaseQuietly(String idempotencyKey) {
        try {
            idempotencyStore.release(idempotencyKey);
        } catch (RuntimeException e) {
            // The lease expires anyway; the retry is only delayed.
            log.warn("could not release claim idempotencyKey={} error={}", idempotencyKey, e.getClass().getSimpleName());
        }
    }

    private void recordAttempt(PremiumPaymentEvent event, AttemptOutcome outcome) {
        try {
            idempotencyStore.recordAttempt(event, outcome);
        } catch (RuntimeException e) {
            // Best effort: must never reject an accepted notification.
            log.warn("could not record attempt idempotencyKey={} error={}",
                    event.data().idempotencyKey(), e.getClass().getSimpleName());
        }
    }

    private static Rejection classify(RuntimeException e) {
        return switch (e) {
            case SignatureVerificationException ignored -> Rejection.SIGNATURE;
            case MalformedNotificationException ignored -> Rejection.MALFORMED;
            case UnsupportedNotificationException ignored -> Rejection.UNSUPPORTED;
            case DeliveryInProgressException ignored -> Rejection.IN_PROGRESS;
            // Broker, store, Key Vault or unexpected: not accepted, the provider retries.
            default -> Rejection.DEPENDENCY_UNAVAILABLE;
        };
    }
}
