package com.meridian.payments.intake.application.port.out;

import java.time.Duration;

/**
 * Business metrics of the intake, always tagged by provider and country so freshness, loss and
 * rejections can be alerted per country (NFR7). Implemented with Micrometer.
 */
public interface IntakeMetrics {

    enum Outcome { PUBLISHED, DUPLICATE }

    enum Rejection { SIGNATURE, MALFORMED, UNSUPPORTED, DEPENDENCY_UNAVAILABLE }

    void accepted(String provider, String country, Outcome outcome, Duration elapsed);

    void rejected(String provider, String country, Rejection reason);
}
