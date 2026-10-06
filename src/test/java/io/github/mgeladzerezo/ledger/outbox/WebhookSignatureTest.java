package io.github.mgeladzerezo.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

    private static final String SECRET = "whsec_test_0123456789";
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    @Test
    void headerVerifiesAgainstTheSameSecretAndBody() {
        String header = WebhookSignature.header(SECRET, NOW, "{\"id\":\"1\"}");

        assertThat(header).matches("t=1772366400,v1=[0-9a-f]{64}");
        assertThat(WebhookSignature.verify(SECRET, header, "{\"id\":\"1\"}", NOW, TOLERANCE)).isTrue();
    }

    @Test
    void tamperedBodyWrongSecretOrEditedTimestampFail() {
        String body = "{\"amount\":100}";
        String header = WebhookSignature.header(SECRET, NOW, body);

        assertThat(WebhookSignature.verify(SECRET, header, "{\"amount\":900}", NOW, TOLERANCE)).isFalse();
        assertThat(WebhookSignature.verify("other-secret", header, body, NOW, TOLERANCE)).isFalse();
        // moving the timestamp to look fresh breaks the HMAC, because the timestamp is signed too
        String forged = header.replace("t=" + NOW.getEpochSecond(), "t=" + (NOW.getEpochSecond() + 60));
        assertThat(WebhookSignature.verify(SECRET, forged, body, NOW, TOLERANCE)).isFalse();
    }

    @Test
    void staleSignatureIsRejectedEvenIfGenuine() {
        String body = "{}";
        String header = WebhookSignature.header(SECRET, NOW, body);

        assertThat(WebhookSignature.verify(SECRET, header, body, NOW.plus(Duration.ofMinutes(4)), TOLERANCE)).isTrue();
        assertThat(WebhookSignature.verify(SECRET, header, body, NOW.plus(Duration.ofMinutes(6)), TOLERANCE)).isFalse();
    }

    @Test
    void malformedHeadersAreRejectedWithoutThrowing() {
        for (String header : new String[] {null, "", "garbage", "t=abc,v1=00", "t=1772366400", "v1=00", "t=,v1="}) {
            assertThat(WebhookSignature.verify(SECRET, header, "{}", NOW, TOLERANCE)).as("header %s", header).isFalse();
        }
    }
}
