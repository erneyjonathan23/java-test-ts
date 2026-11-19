package com.meridian.payments.intake.adapter.in.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.meridian.payments.intake.application.IntakeService;
import com.meridian.payments.intake.application.RequestHeaders;
import com.meridian.payments.intake.config.IntakeProperties;

/** Webhook endpoint. Reads the body as raw bytes because signatures cover the exact bytes. */
@RestController
public class WebhookController {

    private final IntakeService intake;
    private final int maxBodyBytes;

    public WebhookController(IntakeService intake, IntakeProperties properties) {
        this.intake = intake;
        this.maxBodyBytes = properties.maxBodyBytes();
    }

    @PostMapping("/webhooks/{provider}")
    public ResponseEntity<Void> receive(@PathVariable String provider,
                                        @RequestBody byte[] rawBody,
                                        @RequestHeader HttpHeaders headers,
                                        @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) String correlationId) {
        // Covers chunked bodies; declared sizes are rejected earlier by RequestSizeLimitFilter.
        if (rawBody.length > maxBodyBytes) {
            throw new PayloadTooLargeException();
        }
        intake.receive(provider, rawBody, RequestHeaders.of(headers), correlationId);
        // New or duplicate: both are accepted.
        return ResponseEntity.ok().build();
    }
}
