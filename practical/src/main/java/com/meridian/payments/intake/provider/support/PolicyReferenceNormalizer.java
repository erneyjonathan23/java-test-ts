package com.meridian.payments.intake.provider.support;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.meridian.payments.intake.canonical.PolicyReference;
import com.meridian.payments.intake.canonical.ReferenceStatus;

/**
 * Turns the payment reference sent by a provider into a Salesforce policy number
 * ({@code POL-CC-NNNNNNNNN}, upper case). Since Andina Pay 2.3 the reference is whatever the
 * payer typed (INC-0412), so only unambiguous variants are normalized; nothing is guessed.
 */
@Component
public class PolicyReferenceNormalizer {

    private static final Pattern FULL = Pattern.compile("^POL-([A-Z]{2})-(\\d{9})$");
    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d{9}$");

    public PolicyReference normalize(String reference, String country) {
        if (reference == null) {
            return new PolicyReference(null, null, ReferenceStatus.INVALID);
        }
        String compact = reference.strip().replace(" ", "").toUpperCase(Locale.ROOT);

        Matcher full = FULL.matcher(compact);
        if (full.matches()) {
            if (!full.group(1).equals(country)) {
                // A policy of another country paid through this country's provider: do not guess.
                return new PolicyReference(reference, null, ReferenceStatus.INVALID);
            }
            ReferenceStatus status = compact.equals(reference) ? ReferenceStatus.VALID : ReferenceStatus.NORMALIZED;
            return new PolicyReference(reference, compact, status);
        }
        if (DIGITS_ONLY.matcher(compact).matches()) {
            return new PolicyReference(reference, "POL-" + country + "-" + compact, ReferenceStatus.NORMALIZED);
        }
        return new PolicyReference(reference, null, ReferenceStatus.INVALID);
    }
}
