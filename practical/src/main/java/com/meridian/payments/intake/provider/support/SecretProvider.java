package com.meridian.payments.intake.provider.support;

import java.util.List;

/**
 * Source of webhook secrets. Returns every secret currently valid (current and next during a rotation).
 * Production implementation: Azure Key Vault through the app's managed identity, cached for a few minutes.
 */
public interface SecretProvider {

    /**
     * @throws SecretUnavailableException when no secret can be obtained. The request must then be refused
     *                                    with a retryable status, never treated as an invalid signature.
     */
    List<String> secrets(String name);
}
