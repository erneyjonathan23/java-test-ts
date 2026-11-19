package com.meridian.payments.intake.config;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import com.meridian.payments.intake.adapter.out.messaging.TimeLimitedEventPublisher;
import com.meridian.payments.intake.adapter.out.metrics.MicrometerIntakeMetrics;
import com.meridian.payments.intake.adapter.out.stub.EnvironmentSecretProvider;
import com.meridian.payments.intake.adapter.out.stub.InMemoryEventPublisher;
import com.meridian.payments.intake.adapter.out.stub.InMemoryIdempotencyStore;
import com.meridian.payments.intake.application.IntakeService;
import com.meridian.payments.intake.application.port.out.EventPublisher;
import com.meridian.payments.intake.application.port.out.IdempotencyStore;
import com.meridian.payments.intake.application.port.out.IntakeMetrics;
import com.meridian.payments.intake.application.port.out.SecretProvider;
import com.meridian.payments.intake.application.spi.ProviderAdapter;
import com.meridian.payments.intake.domain.PolicyReferenceNormalizer;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Composition root: the only place where the framework-free domain and application layers meet Spring
 * and the concrete adapters.
 * <p>
 * Outside the {@code azure} profile the external dependencies are the in-memory/environment stubs.
 * The {@code azure} profile would contribute the Service Bus, Azure SQL and Key Vault adapters
 * (not part of this deliverable), so a stubbed build can never start silently in Azure.
 */
@Configuration
public class IntakeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IntakeConfiguration.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PolicyReferenceNormalizer policyReferenceNormalizer() {
        return new PolicyReferenceNormalizer();
    }

    @Bean
    IntakeMetrics intakeMetrics(MeterRegistry registry) {
        return new MicrometerIntakeMetrics(registry);
    }

    @Bean
    IntakeService intakeService(List<ProviderAdapter> adapters, IdempotencyStore store, EventPublisher publisher,
                                IntakeMetrics metrics, Clock clock) {
        return new IntakeService(adapters, store, publisher, metrics, clock);
    }

    /** Every publication is time-boxed, whatever the broker adapter is. */
    @Bean
    @Primary
    EventPublisher eventPublisher(BrokerPublisher broker,
                                  @Value("${intake.publish-timeout:3s}") Duration timeout) {
        return new TimeLimitedEventPublisher(broker.publisher(), timeout);
    }

    /** Wrapper so the broker adapter is not itself a candidate for injection as {@link EventPublisher}. */
    public record BrokerPublisher(EventPublisher publisher) {
    }

    @Configuration
    @Profile("!azure")
    static class Stubs {

        @Bean
        InMemoryEventPublisher inMemoryEventPublisher() {
            log.warn("Using STUB in-memory event publisher (no Service Bus)");
            return new InMemoryEventPublisher();
        }

        @Bean
        BrokerPublisher brokerPublisher(InMemoryEventPublisher stub) {
            return new BrokerPublisher(stub);
        }

        @Bean
        InMemoryIdempotencyStore idempotencyStore() {
            log.warn("Using STUB in-memory idempotency store (no Azure SQL)");
            return new InMemoryIdempotencyStore();
        }

        @Bean
        SecretProvider secretProvider(Environment environment) {
            log.warn("Using STUB environment secret provider (no Key Vault)");
            return new EnvironmentSecretProvider(environment);
        }
    }
}
