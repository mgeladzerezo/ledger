package io.github.mgeladzerezo.ledger.outbox;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 webhook signatures, shared by the relay (signing) and the sample consumer
 * (verifying). The header value is {@code t=<unix seconds>,v1=<hex hmac of "t.body">}. Signing
 * the timestamp together with the body lets a consumer reject a captured request replayed later.
 */
public final class WebhookSignature {

    public static final String HEADER = "X-Ledger-Signature";

    private WebhookSignature() {
    }

    public static String header(String secret, Instant now, String body) {
        long timestamp = now.getEpochSecond();
        return "t=" + timestamp + ",v1=" + hmac(secret, timestamp, body);
    }

    /**
     * @param tolerance maximum accepted age (or clock skew) of the signature
     * @return whether {@code header} is a valid, fresh signature of {@code body}
     */
    public static boolean verify(String secret, String header, String body, Instant now, Duration tolerance) {
        if (header == null) {
            return false;
        }
        Long timestamp = null;
        String signature = null;
        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) {
                return false;
            }
            if (pair[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(pair[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (pair[0].equals("v1")) {
                signature = pair[1];
            }
        }
        if (timestamp == null || signature == null) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - timestamp) > tolerance.toSeconds()) {
            return false;
        }
        // constant-time comparison: do not leak how many leading characters matched
        return MessageDigest.isEqual(
                hmac(secret, timestamp, body).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hmac(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
