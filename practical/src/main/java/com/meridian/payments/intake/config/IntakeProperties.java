package com.meridian.payments.intake.config;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Typed intake settings, validated at startup: a wrong value fails fast instead of failing a payment. */
@Validated
@ConfigurationProperties(prefix = "intake")
public record IntakeProperties(
        @DefaultValue("65536") @Min(1024) @Max(1_048_576) int maxBodyBytes,
        @DefaultValue("3s") @NotNull Duration publishTimeout,
        @DefaultValue("10s") @NotNull Duration claimLease) {

    /** Strictest provider timeout is Andina's 5 s; keep a margin for the rest of the request. */
    static final Duration MAX_PUBLISH_TIMEOUT = Duration.ofSeconds(4);

    @AssertTrue(message = "publish-timeout must be at most 4s (Andina times out at 5s)")
    public boolean isPublishTimeoutBelowProviderTimeout() {
        return publishTimeout == null || publishTimeout.compareTo(MAX_PUBLISH_TIMEOUT) <= 0;
    }

    @AssertTrue(message = "claim-lease must be longer than publish-timeout")
    public boolean isLeaseLongerThanPublishTimeout() {
        return publishTimeout == null || claimLease == null || claimLease.compareTo(publishTimeout) > 0;
    }
}
