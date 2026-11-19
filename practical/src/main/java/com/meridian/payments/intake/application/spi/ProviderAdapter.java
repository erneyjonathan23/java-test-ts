package com.meridian.payments.intake.application.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.meridian.payments.intake.application.RequestHeaders;
import com.meridian.payments.intake.domain.PremiumPaymentData;

/** Everything specific to one provider. Adding a provider = one new implementation (NFR9). */
public interface ProviderAdapter {

    /** Path segment of the webhook, e.g. {@code quetzal} for {@code POST /webhooks/quetzal}. */
    String id();

    /** Country served, e.g. MX (for per-country metrics). */
    String country();

    /** Verifies the signature over the raw bytes; throws SignatureVerificationException if not authentic. */
    void verifySignature(byte[] rawBody, RequestHeaders headers, Instant now);

    /** Convenience overload for callers holding a plain header map. */
    default void verifySignature(byte[] rawBody, Map<String, ? extends List<String>> headers, Instant now) {
        verifySignature(rawBody, RequestHeaders.of(headers), now);
    }

    /** Maps the payload to the canonical event; throws Malformed/UnsupportedNotificationException. */
    PremiumPaymentData toCanonical(byte[] rawBody);
}
