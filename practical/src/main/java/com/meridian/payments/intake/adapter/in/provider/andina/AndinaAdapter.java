package com.meridian.payments.intake.adapter.in.provider.andina;

import static com.meridian.payments.intake.adapter.in.provider.support.JsonFields.object;
import static com.meridian.payments.intake.adapter.in.provider.support.JsonFields.offsetDateTime;
import static com.meridian.payments.intake.adapter.in.provider.support.JsonFields.optionalText;
import static com.meridian.payments.intake.adapter.in.provider.support.JsonFields.text;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.intake.domain.PaymentMethodType;
import com.meridian.payments.intake.domain.PaymentStatus;
import com.meridian.payments.intake.domain.PremiumPaymentData;
import com.meridian.payments.intake.domain.MalformedNotificationException;
import com.meridian.payments.intake.application.spi.ProviderAdapter;
import com.meridian.payments.intake.application.RequestHeaders;
import com.meridian.payments.intake.application.SignatureVerificationException;
import com.meridian.payments.intake.domain.UnsupportedNotificationException;
import com.meridian.payments.intake.domain.Amounts;
import com.meridian.payments.intake.adapter.in.provider.support.Hmac;
import com.meridian.payments.intake.adapter.in.provider.support.JsonFields;
import com.meridian.payments.intake.domain.PolicyReferenceNormalizer;
import com.meridian.payments.intake.application.port.out.SecretProvider;

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
    public String country() {
        return COUNTRY;
    }

    @Override
    public void verifySignature(byte[] rawBody, RequestHeaders headers, Instant now) {
        String timestamp = headers.first(TIMESTAMP_HEADER);
        String signature = headers.first(SIGNATURE_HEADER);
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
