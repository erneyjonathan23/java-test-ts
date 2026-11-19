package com.meridian.payments.intake.application.port.out;

import java.time.Duration;

/** Intake metrics, tagged by provider and country for per-country alerts (NFR7). */
public interface IntakeMetrics {

    enum Outcome { PUBLISHED, DUPLICATE }

    enum Rejection { SIGNATURE, MALFORMED, UNSUPPORTED, IN_PROGRESS, DEPENDENCY_UNAVAILABLE }

    void accepted(String provider, String country, Outcome outcome, Duration elapsed);

    void rejected(String provider, String country, Rejection reason);
}
