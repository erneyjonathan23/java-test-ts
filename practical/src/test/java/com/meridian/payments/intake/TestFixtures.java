package com.meridian.payments.intake;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.springframework.http.HttpHeaders;

import com.meridian.payments.intake.adapter.in.provider.support.Hmac;
import com.meridian.payments.intake.application.port.out.SecretProvider;

/**
 * Provider samples and test vectors from reference/providers (read byte for byte from the repository,
 * never copied or re-formatted). The secrets are the providers' published SANDBOX test secrets.
 */
public final class TestFixtures {

    public static final Path PROVIDERS = Path.of("..", "reference", "providers");
    public static final Path EVENT_SCHEMA = Path.of("..", "docs", "api", "events", "premium-payment.schema.json");

    public static final String QUETZAL_TEST_SECRET = "qz_test_whsec_4f8a1c2e9b7d";
    public static final String ANDINA_TEST_SECRET = "andina_test_secret_7c3e19ab";
    public static final String AUSTRAL_TEST_SECRET = "aus_test_whsec_2b9d6f10";

    /** One sample with the headers published in the provider's test vector table. */
    public record Vector(String file, byte[] body, HttpHeaders headers, Instant signedAt) {
    }

    public static final Vector QZ_SUCCEEDED = quetzal("01-charge-succeeded.json", 1795888803L,
            "2d07770582c02f15235ac4e027b7732beef86be103c574a1c3b988a4275f5031");
    public static final Vector QZ_FAILED = quetzal("02-charge-failed.json", 1795889403L,
            "d793d3da0a8d4d66e984ada3daa8b9f473a0eef153d99cdd83bb70f60f13ccc9");
    public static final Vector QZ_REFUNDED = quetzal("03-charge-refunded.json", 1795975203L,
            "a61f9d88d861cd4cc63359b5ca6e6d11d55369db7079387dee9a5024ccb2cae6");

    public static final Vector AN_PENDING = andina("01-transaccion-pendiente.json", "2026-11-28T14:02:59-05:00",
            "JhaBZfk7eQsoMbbQbThmAx/bCyehBOzKYw8XTP2a40k=");
    public static final Vector AN_APPROVED = andina("02-transaccion-aprobada.json", "2026-11-28T14:03:11-05:00",
            "MFQjfviySi6CjqbWtQcy5spKtXxHIxAvmg5W3PTzPJk=");
    public static final Vector AN_APPROVED_RETRY = andina("03-transaccion-aprobada-reintento.json",
            "2026-11-28T14:08:11-05:00", "CNu6m5X6B9bMKTsi/IzZfGquIh/KdxTyesBtL4ax424=");
    public static final Vector AN_REVERSED = andina("04-transaccion-reversada.json", "2026-12-03T09:15:42-05:00",
            "AfGO+MEffk0AWIEEE3cgyff4hWewoOaDhygCWxs/fNI=");

    public static final Vector AU_CONFIRMED = austral("01-payment-confirmed.json", "2026-11-28T17:05:44Z",
            "ec52aeac3cbb26dbb1650a46ea82fd44c38f99dd6705e7ab413b2704e1482357");
    public static final Vector AU_REVERSED = austral("02-payment-reversed.json", "2026-11-30T12:41:09Z",
            "e104a041f3f5fd9bd2bcf2dc746f919fd6f759caeedc450e5f1f247e009d9721");

    public static final List<Vector> ALL_QUETZAL = List.of(QZ_SUCCEEDED, QZ_FAILED, QZ_REFUNDED);
    public static final List<Vector> ALL_ANDINA = List.of(AN_PENDING, AN_APPROVED, AN_APPROVED_RETRY, AN_REVERSED);
    public static final List<Vector> ALL_AUSTRAL = List.of(AU_CONFIRMED, AU_REVERSED);

    private TestFixtures() {
    }

    public static java.util.stream.Stream<Vector> allQuetzal() {
        return ALL_QUETZAL.stream();
    }

    public static java.util.stream.Stream<Vector> allAndina() {
        return ALL_ANDINA.stream();
    }

    public static java.util.stream.Stream<Vector> allAustral() {
        return ALL_AUSTRAL.stream();
    }

    public static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Vector quetzal(String file, long t, String v1) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Quetzal-Signature", "t=" + t + ",v1=" + v1);
        return new Vector(file, read(PROVIDERS.resolve("quetzal-mx/samples/" + file)), headers, Instant.ofEpochSecond(t));
    }

    private static Vector andina(String file, String timestamp, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Andina-Timestamp", timestamp);
        headers.add("X-Andina-Signature", signature);
        return new Vector(file, read(PROVIDERS.resolve("andina-co/samples/" + file)), headers,
                OffsetDateTime.parse(timestamp).toInstant());
    }

    private static Vector austral(String file, String sentAt, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Austral-Signature", signature);
        return new Vector(file, read(PROVIDERS.resolve("austral-cl/samples/" + file)), headers, Instant.parse(sentAt));
    }

    /** Signs an arbitrary body the way Austral does (for crafted test cases). */
    public static HttpHeaders australHeaders(byte[] body, String secret) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Austral-Signature", HexFormat.of().formatHex(Hmac.sha256(secret, body)));
        return headers;
    }

    /** Signs an arbitrary body the way Quetzal does (for crafted test cases). */
    public static HttpHeaders quetzalHeaders(byte[] body, long t, String secret) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Quetzal-Signature", "t=" + t + ",v1="
                + HexFormat.of().formatHex(Hmac.sha256(secret, Long.toString(t), body)));
        return headers;
    }

    /** Signs an arbitrary body the way Andina does (for crafted test cases). */
    public static HttpHeaders andinaHeaders(byte[] body, String timestamp, String secret) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Andina-Timestamp", timestamp);
        headers.add("X-Andina-Signature", Base64.getEncoder().encodeToString(Hmac.sha256(secret, timestamp, body)));
        return headers;
    }

    public static SecretProvider secrets(String... values) {
        return name -> List.of(values);
    }

    /** A clock the tests can move: the vectors are signed in the future (2026-11-28 onward). */
    public static final class MutableClock extends Clock {

        private volatile Instant now;

        public MutableClock(Instant now) {
            this.now = now;
        }

        public void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
