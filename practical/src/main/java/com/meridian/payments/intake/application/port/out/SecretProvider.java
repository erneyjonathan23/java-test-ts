package com.meridian.payments.intake.application.port.out;

import java.util.List;

/** Webhook secrets currently valid (current and next during a rotation). Production: Key Vault. */
public interface SecretProvider {

    /** @throws SecretUnavailableException if no secret is available (answer 503, never 401) */
    List<String> secrets(String name);
}
