package com.meridian.payments.intake.stub;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.meridian.payments.intake.ports.EventPublisher;
import com.meridian.payments.intake.ports.IdempotencyStore;
import com.meridian.payments.intake.provider.support.SecretProvider;

/**
 * Wires the stubs when no real implementation is on the classpath. Real implementations
 * (Service Bus, Azure SQL, Key Vault) would be registered as beans and replace these.
 */
@Configuration
public class StubConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StubConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    InMemoryEventPublisher eventPublisher() {
        log.warn("Using STUB in-memory event publisher (no Service Bus)");
        return new InMemoryEventPublisher();
    }

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    InMemoryIdempotencyStore idempotencyStore() {
        log.warn("Using STUB in-memory idempotency store (no Azure SQL)");
        return new InMemoryIdempotencyStore();
    }

    @Bean
    @ConditionalOnMissingBean(SecretProvider.class)
    SecretProvider secretProvider(Environment environment) {
        log.warn("Using STUB environment secret provider (no Key Vault)");
        return new EnvironmentSecretProvider(environment);
    }
}
