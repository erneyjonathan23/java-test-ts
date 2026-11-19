package com.meridian.payments.intake.provider.andina;

import static com.meridian.payments.intake.provider.support.JsonFields.object;
import static com.meridian.payments.intake.provider.support.JsonFields.offsetDateTime;
import static com.meridian.payments.intake.provider.support.JsonFields.optionalText;
import static com.meridian.payments.intake.provider.support.JsonFields.text;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.intake.canonical.PaymentMethodType;
import com.meridian.payments.intake.canonical.PaymentStatus;
import com.meridian.payments.intake.canonical.PremiumPaymentData;
import com.meridian.payments.intake.provider.MalformedNotificationException;
import com.meridian.payments.intake.provider.ProviderAdapter;
import com.meridian.payments.intake.provider.SignatureVerificationException;
import com.meridian.payments.intake.provider.UnsupportedNotificationException;
import com.meridian.payments.intake.provider.support.Amounts;
import com.meridian.payments.intake.provider.support.Hmac;
import com.meridian.payments.intake.provider.support.JsonFields;
import com.meridian.payments.intake.provider.support.PolicyReferenceNormalizer;
import com.meridian.payments.intake.provider.support.SecretProvider;

/**
 * Andina Pay (Colombia), API version 2.3.
 * Signature: {@code X-Andina-Signature} = Base64(HMAC-SHA256({@code <X-Andina-Timestamp>.<raw_body>})).
 * Retries carry a NEW notificacion_id: the business event is transaccion.id + transaccion.estado.
 */
@Component
public class AndinaAdapter implements ProviderAdapter {

    static final String TIMESTAMP_HEADER = "X-Andina-Timestamp";
    static final String SIGNATURE_HEADER = "X-Andina-Signature";
    static final Duration TOLERANCE = Duration.ofMinutes(10);
    private static final String SECRET_NAME = "andina-webhook";
    private static final String PROVIDER = "ANDINA";
    private static final String COUNTRY = "CO";
    private static final Pattern AMOUNT = Pattern.compile("^\\d+(\\.\\d{1,2})?$");

    private final SecretProvider secrets;
    private final JsonFields json;
    private final PolicyReferenceNormalizer policyReferences;

    public AndinaAdapter(SecretProvider secrets, ObjectMapper mapper, PolicyReferenceNormalizer policyReferences) {
        this.secrets = secrets;
        this.json = new JsonFields(mapper);
        this.policyReferences = policyReferences;
    }

    @Override
    public String id() {
        return "andina";
    }

    @Override
    public void verifySignature(byte[] rawBody, HttpHeaders headers, Instant now) {
        String timestamp = headers.getFirst(TIMESTAMP_HEADER);
        String signature = headers.getFirst(SIGNATURE_HEADER);
        if (timestamp == null || timestamp.isBlank() || signature == null || signature.isBlank()) {
            throw new SignatureVerificationException("missing Andina signature headers");
        }

        Instant signedAt;
        try {
            signedAt = OffsetDateTime.parse(timestamp).toInstant();
        } catch (DateTimeParseException e) {
            throw new SignatureVerificationException("malformed " + TIMESTAMP_HEADER);
        }
        if (Duration.between(signedAt, now).abs().compareTo(TOLERANCE) > 0) {
            throw new SignatureVerificationException("timestamp outside tolerance");
        }

        byte[] provided;
        try {
            provided = Base64.getDecoder().decode(signature.trim());
        } catch (IllegalArgumentException e) {
            throw new SignatureVerificationException("signature is not Base64");
        }

        // The signed content uses the timestamp header exactly as received.
        for (String secret : secrets.secrets(SECRET_NAME)) {
            if (Hmac.constantTimeEquals(Hmac.sha256(secret, timestamp, rawBody), provided)) {
                return;
            }
        }
        throw new SignatureVerificationException("signature does not match");
    }

    @Override
    public PremiumPaymentData toCanonical(byte[] rawBody) {
        JsonNode root = json.parse(rawBody);
        String notificationId = text(root, "notificacion_id");
        JsonNode trx = object(root, "transaccion");

        String transactionId = text(trx, "id");
        String state = text(trx, "estado");
        PaymentStatus status = switch (state) {
            case "PENDIENTE" -> PaymentStatus.PENDING;
            case "APROBADA" -> PaymentStatus.CAPTURED;
            case "RECHAZADA" -> PaymentStatus.FAILED;
            case "REVERSADA" -> PaymentStatus.REFUNDED;
            default -> throw new UnsupportedNotificationException("unsupported Andina state " + state);
        };

        String value = text(trx, "valor");
        if (!AMOUNT.matcher(value).matches()) {
            throw new MalformedNotificationException("invalid amount transaccion.valor");
        }
        String currency = text(trx, "moneda");

        // transaccion.pagador (name, national ID, e-mail) is deliberately never read.
        return new PremiumPaymentData(
                PremiumPaymentData.SCHEMA_VERSION,
                PROVIDER + ":" + transactionId + ":" + state,
                PROVIDER + ":" + transactionId,
                COUNTRY,
                PROVIDER,
                transactionId,
                notificationId,
                state,
                status,
                offsetDateTime(trx, "fecha_transaccion"),
                Amounts.fromMajorUnits(new BigDecimal(value), currency),
                null,
                policyReferences.normalize(text(trx, "referencia"), COUNTRY),
                paymentMethod(optionalText(trx, "medio_pago")),
                status == PaymentStatus.REFUNDED ? optionalText(trx, "motivo_reverso") : null);
    }

    private static PaymentMethodType paymentMethod(String medioPago) {
        if (medioPago == null) {
            return PaymentMethodType.OTHER;
        }
        return switch (medioPago) {
            case "TARJETA" -> PaymentMethodType.CARD;
            case "PSE" -> PaymentMethodType.BANK_TRANSFER;
            case "EFECTIVO" -> PaymentMethodType.CASH;
            default -> PaymentMethodType.OTHER;
        };
    }
}
