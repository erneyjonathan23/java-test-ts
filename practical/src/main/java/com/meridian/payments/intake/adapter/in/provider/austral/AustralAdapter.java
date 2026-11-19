package com.meridian.payments.intake.adapter.in.provider.austral;

import static com.meridian.payments.intake.adapter.in.provider.support.JsonFields.object;
import static com.meridian.payments.intake.adapter.in.provider.support.JsonFields.offsetDateTime;
import static com.meridian.payments.intake.adapter.in.provider.support.JsonFields.text;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.intake.adapter.in.provider.support.Hmac;
import com.meridian.payments.intake.adapter.in.provider.support.JsonFields;
import com.meridian.payments.intake.application.RequestHeaders;
import com.meridian.payments.intake.application.SignatureVerificationException;
import com.meridian.payments.intake.application.port.out.SecretProvider;
import com.meridian.payments.intake.application.spi.ProviderAdapter;
import com.meridian.payments.intake.domain.Amounts;
import com.meridian.payments.intake.domain.MalformedNotificationException;
import com.meridian.payments.intake.domain.PaymentMethodType;
import com.meridian.payments.intake.domain.PaymentStatus;
import com.meridian.payments.intake.domain.PolicyReferenceNormalizer;
import com.meridian.payments.intake.domain.PremiumPaymentData;
import com.meridian.payments.intake.domain.UnsupportedNotificationException;

/**
 * Austral Pagos (CL). Signature: hex HMAC-SHA256 of the body (no timestamp),
 * so replays are stopped by idempotency on eventId.
 */
@Component
public class AustralAdapter implements ProviderAdapter {

    static final String SIGNATURE_HEADER = "X-Austral-Signature";
    private static final String SECRET_NAME = "austral-webhook";
    private static final String PROVIDER = "AUSTRAL";
    private static final String COUNTRY = "CL";
    private static final Pattern AMOUNT = Pattern.compile("^\\d+(\\.\\d+)?$");

    private final SecretProvider secrets;
    private final JsonFields json;
    private final PolicyReferenceNormalizer policyReferences;

    public AustralAdapter(SecretProvider secrets, ObjectMapper mapper, PolicyReferenceNormalizer policyReferences) {
        this.secrets = secrets;
        this.json = new JsonFields(mapper);
        this.policyReferences = policyReferences;
    }

    @Override
    public String id() {
        return "austral";
    }

    @Override
    public String country() {
        return COUNTRY;
    }

    @Override
    public void verifySignature(byte[] rawBody, RequestHeaders headers, Instant now) {
        String signature = headers.first(SIGNATURE_HEADER);
        if (signature == null || signature.isBlank()) {
            throw new SignatureVerificationException("missing " + SIGNATURE_HEADER);
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signature.trim());
        } catch (IllegalArgumentException e) {
            throw new SignatureVerificationException("signature is not hexadecimal");
        }
        for (String secret : secrets.secrets(SECRET_NAME)) {
            if (Hmac.constantTimeEquals(Hmac.sha256(secret, rawBody), provided)) {
                return;
            }
        }
        throw new SignatureVerificationException("signature does not match");
    }

    @Override
    public PremiumPaymentData toCanonical(byte[] rawBody) {
        JsonNode root = json.parse(rawBody);
        String eventId = text(root, "eventId");
        String eventType = text(root, "eventType");
        PaymentStatus status = switch (eventType) {
            case "PAYMENT_CONFIRMED" -> PaymentStatus.CAPTURED;
            case "PAYMENT_REJECTED" -> PaymentStatus.FAILED;
            case "PAYMENT_REVERSED" -> PaymentStatus.REFUNDED;
            default -> throw new UnsupportedNotificationException("unsupported Austral event type " + eventType);
        };

        JsonNode payment = object(root, "payment");
        String paymentId = text(payment, "paymentId");
        String amount = text(payment, "amount");
        if (!AMOUNT.matcher(amount).matches()) {
            throw new MalformedNotificationException("invalid amount payment.amount");
        }

        // payer (name, RUT, e-mail) is never read: no personal data.
        return new PremiumPaymentData(
                PremiumPaymentData.SCHEMA_VERSION,
                PROVIDER + ":" + eventId,
                PROVIDER + ":" + paymentId,
                COUNTRY,
                PROVIDER,
                paymentId,
                eventId,
                eventType,
                status,
                offsetDateTime(payment, "occurredAt"),
                // CLP has no decimals: rejected, never rounded.
                Amounts.fromMajorUnits(new BigDecimal(amount), text(payment, "currency")),
                null,
                policyReferences.normalize(text(payment, "policyNumber"), COUNTRY),
                PaymentMethodType.OTHER,
                null);
    }
}
