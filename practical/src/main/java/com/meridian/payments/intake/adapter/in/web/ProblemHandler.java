package com.meridian.payments.intake.adapter.in.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.meridian.payments.intake.application.DeliveryInProgressException;
import com.meridian.payments.intake.application.SignatureVerificationException;
import com.meridian.payments.intake.application.UnknownProviderException;
import com.meridian.payments.intake.application.port.out.PublishException;
import com.meridian.payments.intake.application.port.out.SecretUnavailableException;
import com.meridian.payments.intake.application.port.out.StoreUnavailableException;
import com.meridian.payments.intake.domain.MalformedNotificationException;
import com.meridian.payments.intake.domain.UnsupportedNotificationException;

/** Maps errors to HTTP statuses. Any non-2xx means "not accepted, retry". Never echoes request data. */
@RestControllerAdvice
public class ProblemHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    @ExceptionHandler(SignatureVerificationException.class)
    ProblemDetail invalidSignature(SignatureVerificationException e) {
        log.warn("rejected notification: signature verification failed reason={}", e.getMessage());
        return problem(HttpStatus.UNAUTHORIZED, "Invalid signature");
    }

    @ExceptionHandler({MalformedNotificationException.class, HttpMessageNotReadableException.class})
    ProblemDetail malformed(RuntimeException e) {
        log.warn("rejected notification: malformed reason={}", e.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Malformed notification");
    }

    @ExceptionHandler(UnsupportedNotificationException.class)
    ProblemDetail unsupported(UnsupportedNotificationException e) {
        // Authentic but unknown event: alert and fix the adapter; the provider keeps retrying.
        log.error("rejected notification: unsupported reason={}", e.getMessage());
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Unsupported notification");
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ProblemDetail tooLarge(PayloadTooLargeException e) {
        log.warn("rejected notification: body too large");
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "Payload too large");
    }

    @ExceptionHandler(UnknownProviderException.class)
    ProblemDetail unknownProvider(UnknownProviderException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found");
    }

    @ExceptionHandler(DeliveryInProgressException.class)
    ProblemDetail inProgress(DeliveryInProgressException e) {
        log.info("notification not accepted: same event is being published by a concurrent delivery");
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable, retry later");
    }

    @ExceptionHandler({PublishException.class, StoreUnavailableException.class, SecretUnavailableException.class})
    ProblemDetail unavailable(RuntimeException e) {
        log.error("notification not accepted: dependency unavailable error={}", e.getClass().getSimpleName());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable, retry later");
    }

    private static ProblemDetail problem(HttpStatus status, String title) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setProperty("correlationId", MDC.get(CorrelationIdFilter.ATTRIBUTE));
        return problem;
    }
}
