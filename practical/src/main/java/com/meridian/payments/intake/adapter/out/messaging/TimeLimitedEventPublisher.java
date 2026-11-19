package com.meridian.payments.intake.adapter.out.messaging;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.meridian.payments.intake.application.port.out.EventPublisher;
import com.meridian.payments.intake.application.port.out.PublishException;
import com.meridian.payments.intake.domain.PremiumPaymentEvent;

/**
 * Decorator that bounds how long a publication may take (NFR4). The intake must answer before the
 * strictest provider timeout (Andina: 5 s); a broker that hangs must turn into a fast {@code 503}
 * (not accepted, provider retries), never into a provider-side timeout.
 * <p>
 * If the slow publication completes after the timeout, the event is published although we answered
 * {@code 503}: the provider's retry then produces a duplicate, which downstream idempotency absorbs.
 * Losing a payment is impossible; duplicating one is harmless.
 */
public class TimeLimitedEventPublisher implements EventPublisher, AutoCloseable {

    private final EventPublisher delegate;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public TimeLimitedEventPublisher(EventPublisher delegate, Duration timeout) {
        this.delegate = delegate;
        this.timeout = timeout;
    }

    @Override
    public void publish(PremiumPaymentEvent event) {
        Future<?> future = executor.submit(() -> delegate.publish(event));
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new PublishException("publish timed out after " + timeout.toMillis() + " ms", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof PublishException publishException) {
                throw publishException;
            }
            throw new PublishException("publish failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("interrupted while publishing", e);
        }
    }

    @Override
    public void close() {
        executor.close();
    }
}
