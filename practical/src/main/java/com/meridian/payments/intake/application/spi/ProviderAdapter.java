package com.meridian.payments.intake.application.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.meridian.payments.intake.application.RequestHeaders;
import com.meridian.payments.intake.domain.PremiumPaymentData;

/**
 * Service provider interface: everything that is specific to one payment provider (anti-corruption layer
 * between the provider's payload and our domain). The shared intake flow only talks to this interface,
 * so a new country or provider is a new implementation and nothing else changes (NFR9).
 */
public interface ProviderAdapter {

    /** Path segment of the webhook, e.g. {@code quetzal} for {@code POST /webhooks/quetzal}. */
    String id();

    /** ISO 3166 country served by this provider, e.g. {@code MX}. Used for metrics and alerts per country. */
    String country();

    /**
     * Verifies authenticity exactly as the provider specifies, over the raw body bytes.
     *
     * @throws com.meridian.payments.intake.application.SignatureVerificationException when the request must not be trusted
     */
    void verifySignature(byte[] rawBody, RequestHeaders headers, Instant now);

    /** Convenience overload for callers holding a plain header map. */
    default void verifySignature(byte[] rawBody, Map<String, ? extends List<String>> headers, Instant now) {
        verifySignature(rawBody, RequestHeaders.of(headers), now);
    }

    /**
     * Translates an authenticated notification into the canonical payload, including its idempotency key.
     *
     * @throws com.meridian.payments.intake.domain.MalformedNotificationException   when required data is missing or invalid
     * @throws com.meridian.payments.intake.domain.UnsupportedNotificationException when the event type, state or currency is unknown
     */
    PremiumPaymentData toCanonical(byte[] rawBody);
}
