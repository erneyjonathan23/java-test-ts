package com.meridian.payments.intake.adapter.out.stub;

import java.util.Arrays;
import java.util.List;

import org.springframework.core.env.Environment;

import com.meridian.payments.intake.application.port.out.SecretProvider;
import com.meridian.payments.intake.application.port.out.SecretUnavailableException;

/** Key Vault stub: reads intake.secrets.<name> (comma-separated, current first) from env vars. */
public class EnvironmentSecretProvider implements SecretProvider {

    private final Environment environment;

    public EnvironmentSecretProvider(Environment environment) {
        this.environment = environment;
    }

    @Override
    public List<String> secrets(String name) {
        String value = environment.getProperty("intake.secrets." + name, "");
        List<String> secrets = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (secrets.isEmpty()) {
            throw new SecretUnavailableException("no secret configured for " + name);
        }
        return secrets;
    }
}
