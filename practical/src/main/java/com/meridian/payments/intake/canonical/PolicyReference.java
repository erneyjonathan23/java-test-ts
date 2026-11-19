package com.meridian.payments.intake.canonical;

/**
 * @param reference       the reference exactly as received from the provider
 * @param number          normalized policy number ({@code POL-CC-NNNNNNNNN}) or {@code null} when invalid
 * @param referenceStatus how the number was obtained
 */
public record PolicyReference(String reference, String number, ReferenceStatus referenceStatus) {
}
