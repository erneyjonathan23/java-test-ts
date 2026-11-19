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
 * Caps publish time below the provider timeout (NFR4): a hung broker becomes a fast 503.
 * A late success only causes a duplicate, which downstream idempotency absorbs.
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
