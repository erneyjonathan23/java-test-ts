package com.meridian.payments.intake.domain;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.meridian.payments.intake.domain.PolicyReference;
import com.meridian.payments.intake.domain.ReferenceStatus;

/** Normalizes the payer-typed reference to POL-CC-NNNNNNNNN (INC-0412). Never guesses. */
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
                // Another country's policy: do not guess.
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
