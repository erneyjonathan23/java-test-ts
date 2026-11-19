package com.meridian.payments.intake.provider.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 over {@code <prefix>.<raw body>} and constant-time comparison. */
public final class Hmac {

    private static final String ALGORITHM = "HmacSHA256";

    private Hmac() {
    }

    /** HMAC-SHA256 of {@code prefix + "." + rawBody}, without re-encoding the body bytes. */
    public static byte[] sha256(String secret, String prefix, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update(prefix.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            return mac.doFinal(rawBody);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    /** Constant-time comparison: does not leak how many leading bytes match. */
    public static boolean constantTimeEquals(byte[] expected, byte[] actual) {
        return MessageDigest.isEqual(expected, actual);
    }
}
