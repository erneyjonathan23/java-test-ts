package com.meridian.payments.intake.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.intake.TestFixtures;
import com.meridian.payments.intake.adapter.in.provider.quetzal.QuetzalAdapter;
import com.meridian.payments.intake.adapter.out.stub.InMemoryEventPublisher;
import com.meridian.payments.intake.application.port.out.PublishException;
import com.meridian.payments.intake.domain.PolicyReferenceNormalizer;
import com.meridian.payments.intake.domain.PremiumPaymentEvent;

class TimeLimitedEventPublisherTest {

    private static final PremiumPaymentEvent EVENT = PremiumPaymentEvent.wrap(
            new QuetzalAdapter(TestFixtures.secrets("unused"), new ObjectMapper(), new PolicyReferenceNormalizer())
                    .toCanonical(TestFixtures.QZ_SUCCEEDED.body()),
            "quetzal", Instant.parse("2026-11-28T18:00:04Z"), "corr");

    @Test
    void publishesThroughTheDelegateWithinTheTimeout() {
        InMemoryEventPublisher broker = new InMemoryEventPublisher();
        try (TimeLimitedEventPublisher publisher = new TimeLimitedEventPublisher(broker, Duration.ofSeconds(1))) {
            publisher.publish(EVENT);
        }
        assertThat(broker.published()).containsExactly(EVENT);
    }

    @Test
    void aHangingBrokerBecomesAFastPublishExceptionNotAProviderTimeout() {
        CountDownLatch never = new CountDownLatch(1);
        try (TimeLimitedEventPublisher publisher = new TimeLimitedEventPublisher(event -> {
            try {
                never.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, Duration.ofMillis(200))) {
            long started = System.nanoTime();

            assertThatThrownBy(() -> publisher.publish(EVENT))
                    .isInstanceOf(PublishException.class)
                    .hasMessageContaining("timed out");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        }
    }

    @Test
    void brokerFailuresAreReportedAsPublishException() {
        try (TimeLimitedEventPublisher publisher = new TimeLimitedEventPublisher(event -> {
            throw new IllegalStateException("connection refused");
        }, Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> publisher.publish(EVENT))
                    .isInstanceOf(PublishException.class)
                    .hasRootCauseMessage("connection refused");
        }
    }
}
