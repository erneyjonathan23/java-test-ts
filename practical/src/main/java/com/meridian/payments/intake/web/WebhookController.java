package com.meridian.payments.intake.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.meridian.payments.intake.IntakeService;

/**
 * Webhook endpoints (contract: docs/api/webhooks.openapi.yaml). The body is taken as raw bytes:
 * signatures are computed over the exact bytes received, so the JSON is never re-serialized
 * before verification.
 */
@RestController
public class WebhookController {

    private final IntakeService intake;
    private final int maxBodyBytes;

    public WebhookController(IntakeService intake, @Value("${intake.max-body-bytes:65536}") int maxBodyBytes) {
        this.intake = intake;
        this.maxBodyBytes = maxBodyBytes;
    }

    @PostMapping("/webhooks/{provider}")
    public ResponseEntity<Void> receive(@PathVariable String provider,
                                        @RequestBody byte[] rawBody,
                                        @RequestHeader HttpHeaders headers,
                                        @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) String correlationId) {
        if (rawBody.length > maxBodyBytes) {
            throw new PayloadTooLargeException();
        }
        intake.receive(provider, rawBody, headers, correlationId);
        // Same answer for new and duplicate notifications: both are accepted.
        return ResponseEntity.ok().build();
    }
}
