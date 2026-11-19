package com.meridian.payments.intake.adapter.out.metrics;

import java.time.Duration;
import java.util.Locale;

import com.meridian.payments.intake.application.port.out.IntakeMetrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Micrometer implementation. Exported to Application Insights in Azure; the names and tags are the ones
 * used by the alerts in infra/modules/monitoring.bicep and docs/operations/OPERATIONS.md.
 */
public class MicrometerIntakeMetrics implements IntakeMetrics {

    public static final String ACCEPTED = "intake.accepted";
    public static final String REJECTED = "intake.rejected";
    public static final String LATENCY = "intake.latency";

    private final MeterRegistry registry;

    public MicrometerIntakeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void accepted(String provider, String country, Outcome outcome, Duration elapsed) {
        Counter.builder(ACCEPTED)
                .description("Notifications accepted (2xx), new or duplicate")
                .tag("provider", provider)
                .tag("country", country)
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .register(registry)
                .increment();
        Timer.builder(LATENCY)
                .description("Time to accept a notification; must stay well below the provider timeout")
                .tag("provider", provider)
                .tag("country", country)
                .publishPercentiles(0.95, 0.99)
                .register(registry)
                .record(elapsed);
    }

    @Override
    public void rejected(String provider, String country, Rejection reason) {
        Counter.builder(REJECTED)
                .description("Notifications not accepted, by reason")
                .tag("provider", provider)
                .tag("country", country)
                .tag("reason", reason.name().toLowerCase(Locale.ROOT))
                .register(registry)
                .increment();
    }
}
