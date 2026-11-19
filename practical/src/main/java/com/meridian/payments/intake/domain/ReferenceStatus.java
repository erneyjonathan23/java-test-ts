package com.meridian.payments.intake.domain;

public enum ReferenceStatus {
    /** Received exactly in the canonical policy number format. */
    VALID,
    /** Received in a recognised variant (case, spaces, missing prefix) and normalized. */
    NORMALIZED,
    /** Cannot be turned into a policy number of the provider's country; parked downstream. */
    INVALID
}
