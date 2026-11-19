package com.meridian.payments.intake.provider;

import java.time.Instant;

import org.springframework.http.HttpHeaders;

import com.meridian.payments.intake.canonical.PremiumPaymentData;

/**
 * Everything that is specific to one payment provider. The shared intake flow only talks to this
 * interface, so a new country or provider is a new implementation (NFR9).
 */
public interface ProviderAdapter {

    /** Path segment of the webhook, e.g. {@code quetzal} for {@code POST /webhooks/quetzal}. */
    String id();

    /**
     * Verifies authenticity exactly as the provider specifies, over the raw body bytes.
     *
     * @throws SignatureVerificationException when the request must not be trusted
     */
    void verifySignature(byte[] rawBody, HttpHeaders headers, Instant now);

    /**
     * Translates an authenticated notification into the canonical payload, including its idempotency key.
     *
     * @throws MalformedNotificationException   when required data is missing or invalid
     * @throws UnsupportedNotificationException when the event type, state or currency is unknown
     */
    PremiumPaymentData toCanonical(byte[] rawBody);
}
