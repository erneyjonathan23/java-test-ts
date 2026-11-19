package com.meridian.payments.intake.provider.quetzal;

import static com.meridian.payments.intake.provider.support.JsonFields.integer;
import static com.meridian.payments.intake.provider.support.JsonFields.object;
import static com.meridian.payments.intake.provider.support.JsonFields.optionalEpochSeconds;
import static com.meridian.payments.intake.provider.support.JsonFields.optionalText;
import static com.meridian.payments.intake.provider.support.JsonFields.text;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.intake.canonical.Money;
import com.meridian.payments.intake.canonical.PaymentMethodType;
import com.meridian.payments.intake.canonical.PaymentStatus;
import com.meridian.payments.intake.canonical.PremiumPaymentData;
import com.meridian.payments.intake.provider.ProviderAdapter;
import com.meridian.payments.intake.provider.SignatureVerificationException;
import com.meridian.payments.intake.provider.UnsupportedNotificationException;
import com.meridian.payments.intake.provider.support.Amounts;
import com.meridian.payments.intake.provider.support.Hmac;
import com.meridian.payments.intake.provider.support.JsonFields;
import com.meridian.payments.intake.provider.support.PolicyReferenceNormalizer;
import com.meridian.payments.intake.provider.support.SecretProvider;

/**
 * Quetzal Pagos (Mexico), API version 2025-06-01.
 * Signature: {@code Quetzal-Signature: t=<unix>,v1=<hex>[,v1=<hex>]} = hex HMAC-SHA256 of {@code <t>.<raw_body>}.
 */
@Component
public class QuetzalAdapter implements ProviderAdapter {

    static final String SIGNATURE_HEADER = "Quetzal-Signature";
    static final Duration TOLERANCE = Duration.ofSeconds(300);
    private static final String SECRET_NAME = "quetzal-webhook";
    private static final String PROVIDER = "QUETZAL";
    private static final String COUNTRY = "MX";

    private final SecretProvider secrets;
    private final JsonFields json;
    private final PolicyReferenceNormalizer policyReferences;

    public QuetzalAdapter(SecretProvider secrets, ObjectMapper mapper, PolicyReferenceNormalizer policyReferences) {
        this.secrets = secrets;
        this.json = new JsonFields(mapper);
        this.policyReferences = policyReferences;
    }

    @Override
    public String id() {
        return "quetzal";
    }

    @Override
    public void verifySignature(byte[] rawBody, HttpHeaders headers, Instant now) {
        String header = headers.getFirst(SIGNATURE_HEADER);
        if (header == null || header.isBlank()) {
            throw new SignatureVerificationException("missing " + SIGNATURE_HEADER);
        }

        String timestamp = null;
        List<byte[]> candidates = new ArrayList<>();
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                throw new SignatureVerificationException("malformed " + SIGNATURE_HEADER);
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            switch (key) {
                case "t" -> {
                    if (timestamp != null) {
                        throw new SignatureVerificationException("duplicate timestamp");
                    }
                    timestamp = value;
                }
                case "v1" -> candidates.add(parseHex(value));
                default -> {
                    // Unknown schemes (e.g. a future v2) are ignored, as long as one v1 matches.
                }
            }
        }
        if (timestamp == null || candidates.isEmpty()) {
            throw new SignatureVerificationException("timestamp or v1 signature missing");
        }

        long t;
        try {
            t = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new SignatureVerificationException("timestamp is not a number");
        }
        if (Math.abs(now.getEpochSecond() - t) > TOLERANCE.toSeconds()) {
            throw new SignatureVerificationException("timestamp outside tolerance");
        }

        // The signed content uses the timestamp exactly as received.
        for (String secret : secrets.secrets(SECRET_NAME)) {
            byte[] expected = Hmac.sha256(secret, timestamp, rawBody);
            for (byte[] candidate : candidates) {
                if (Hmac.constantTimeEquals(expected, candidate)) {
                    return;
                }
            }
        }
        throw new SignatureVerificationException("no signature matches");
    }

    @Override
    public PremiumPaymentData toCanonical(byte[] rawBody) {
        JsonNode root = json.parse(rawBody);
        String eventId = text(root, "id");
        String type = text(root, "type");
        PaymentStatus status = switch (type) {
            case "charge.succeeded" -> PaymentStatus.CAPTURED;
            case "charge.failed" -> PaymentStatus.FAILED;
            case "charge.refunded" -> PaymentStatus.REFUNDED;
            default -> throw new UnsupportedNotificationException("unsupported Quetzal event type " + type);
        };
        Instant created = Instant.ofEpochSecond(integer(root, "created"));

        JsonNode charge = object(object(root, "data"), "charge");
        String chargeId = text(charge, "id");
        String currency = text(charge, "currency").toUpperCase(Locale.ROOT);
        Money amount = Amounts.fromMinorUnits(integer(charge, "amount"), currency);

        Instant statusChangedAt = switch (status) {
            case CAPTURED -> orDefault(optionalEpochSeconds(charge, "paid_at"), created);
            case REFUNDED -> orDefault(optionalEpochSeconds(charge, "refunded_at"), created);
            default -> created;
        };
        Money amountRefunded = status == PaymentStatus.REFUNDED && charge.hasNonNull("amount_refunded")
                ? Amounts.fromMinorUnits(integer(charge, "amount_refunded"), currency)
                : null;
        String reason = status == PaymentStatus.FAILED ? optionalText(charge, "failure_code") : null;

        // data.charge.customer (payer e-mail) is deliberately never read.
        return new PremiumPaymentData(
                PremiumPaymentData.SCHEMA_VERSION,
                PROVIDER + ":" + eventId,
                PROVIDER + ":" + chargeId,
                COUNTRY,
                PROVIDER,
                chargeId,
                eventId,
                type,
                status,
                statusChangedAt,
                amount,
                amountRefunded,
                policyReferences.normalize(text(charge, "reference"), COUNTRY),
                paymentMethod(charge),
                reason);
    }

    private static PaymentMethodType paymentMethod(JsonNode charge) {
        JsonNode method = charge.get("payment_method");
        String type = method == null ? null : optionalText(method, "type");
        if (type == null) {
            return PaymentMethodType.OTHER;
        }
        return switch (type) {
            case "card" -> PaymentMethodType.CARD;
            case "bank_transfer", "spei" -> PaymentMethodType.BANK_TRANSFER;
            case "cash", "oxxo" -> PaymentMethodType.CASH;
            default -> PaymentMethodType.OTHER;
        };
    }

    private static byte[] parseHex(String value) {
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException e) {
            throw new SignatureVerificationException("v1 is not hexadecimal");
        }
    }

    private static Instant orDefault(Instant value, Instant fallback) {
        return value != null ? value : fallback;
    }
}
