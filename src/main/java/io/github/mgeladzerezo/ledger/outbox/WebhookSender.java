package io.github.mgeladzerezo.ledger.outbox;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;

import io.github.mgeladzerezo.ledger.config.LedgerProperties;
import io.github.mgeladzerezo.ledger.outbox.OutboxRepository.ClaimedDelivery;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sends one event to one subscriber as a signed HTTP POST. Any 2xx is success; everything else,
 * including a timeout or a refused connection, is a failure the relay will retry.
 */
@Component
public class WebhookSender {

    public static final String EVENT_ID_HEADER = "X-Ledger-Event-Id";
    public static final String EVENT_TYPE_HEADER = "X-Ledger-Event-Type";
    public static final String ATTEMPT_HEADER = "X-Ledger-Delivery-Attempt";

    private final HttpClient http;
    private final JsonMapper json;
    private final Duration timeout;
    private final Clock clock = Clock.systemUTC();

    public WebhookSender(JsonMapper json, LedgerProperties properties) {
        this.json = json;
        this.timeout = properties.outbox().httpTimeout();
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public SendResult send(ClaimedDelivery delivery) {
        String body = envelope(delivery);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(delivery.url()))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header(EVENT_ID_HEADER, delivery.eventId().toString())
                    .header(EVENT_TYPE_HEADER, delivery.eventType())
                    .header(ATTEMPT_HEADER, Integer.toString(delivery.attempts() + 1))
                    .header(WebhookSignature.HEADER, WebhookSignature.header(delivery.secret(), clock.instant(), body))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return status / 100 == 2
                    ? new SendResult(true, status, null)
                    : new SendResult(false, status, "subscriber answered HTTP " + status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SendResult(false, null, "interrupted");
        } catch (IOException | IllegalArgumentException e) {
            return new SendResult(false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** The JSON document a subscriber receives. {@code id} is the deduplication key. */
    String envelope(ClaimedDelivery delivery) {
        ObjectNode envelope = json.createObjectNode();
        envelope.put("id", delivery.eventId().toString());
        envelope.put("type", delivery.eventType());
        envelope.put("sequence", delivery.sequence());
        envelope.put("createdAt", delivery.createdAt().toString());
        envelope.set("data", json.readTree(delivery.payload()));
        return json.writeValueAsString(envelope);
    }

    /**
     * @param httpStatus status the subscriber answered with, or {@code null} if no response arrived
     */
    public record SendResult(boolean success, Integer httpStatus, String error) {
    }
}
