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
import com.meridian.payments.intake.application.port.out.IdempotencyStore;
import com.meridian.payments.intake.application.port.out.IdempotencyStore.AttemptOutcome;
import com.meridian.payments.intake.application.port.out.IdempotencyStore.ClaimResult;
import com.meridian.payments.intake.application.port.out.IntakeMetrics;
import com.meridian.payments.intake.application.port.out.IntakeMetrics.Outcome;
import com.meridian.payments.intake.application.port.out.IntakeMetrics.Rejection;
import com.meridian.payments.intake.application.port.out.PublishException;
import com.meridian.payments.intake.application.port.out.SecretUnavailableException;
import com.meridian.payments.intake.application.port.out.StoreUnavailableException;
import com.meridian.payments.intake.application.spi.ProviderAdapter;
import com.meridian.payments.intake.domain.MalformedNotificationException;
import com.meridian.payments.intake.domain.PremiumPaymentData;
import com.meridian.payments.intake.domain.PremiumPaymentEvent;
import com.meridian.payments.intake.domain.UnsupportedNotificationException;

/**
 * Use case "receive a payment notification", identical for every provider:
 * verify signature → map to canonical → claim idempotency key → publish → acknowledge.
 * <p>
 * A notification is accepted (2xx) only after the event has been durably published (NFR3).
 * Every exception thrown from here means "not accepted" and is translated to a non-2xx status by the
 * inbound adapter, so the provider retries. Framework-free: wired in {@code config}.
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

    private static Rejection classify(RuntimeException e) {
        return switch (e) {
            case SignatureVerificationException ignored -> Rejection.SIGNATURE;
            case MalformedNotificationException ignored -> Rejection.MALFORMED;
            case UnsupportedNotificationException ignored -> Rejection.UNSUPPORTED;
            case PublishException ignored -> Rejection.DEPENDENCY_UNAVAILABLE;
            case StoreUnavailableException ignored -> Rejection.DEPENDENCY_UNAVAILABLE;
            case SecretUnavailableException ignored -> Rejection.DEPENDENCY_UNAVAILABLE;
            default -> Rejection.DEPENDENCY_UNAVAILABLE;
        };
    }
}
