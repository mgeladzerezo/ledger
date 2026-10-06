package io.github.mgeladzerezo.ledger.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import io.github.mgeladzerezo.ledger.config.LedgerProperties;
import io.github.mgeladzerezo.ledger.domain.LedgerException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints that exist only for the bundled dashboard. */
@RestController
public class UiController {

    private final LedgerProperties properties;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public UiController(LedgerProperties properties) {
        this.properties = properties;
    }

    /**
     * Public bootstrap information for the dashboard. In demo mode, and only then, it hands the
     * page the demo API key so the dashboard works straight after {@code docker compose up};
     * otherwise the page asks the user for a key.
     */
    @GetMapping("/ui/config")
    UiConfig config() {
        boolean demo = properties.demo().enabled();
        return new UiConfig(demo, properties.chaos().enabled(),
                demo ? properties.security().apiKeys().get("demo") : null,
                properties.demo().consumerStatsUrl() != null);
    }

    /** Relays the sample consumer's counters so the dashboard can show both ends of a delivery. */
    @GetMapping("/api/v1/demo/consumer")
    ResponseEntity<String> consumerStats() {
        String url = properties.demo().consumerStatsUrl();
        if (url == null || url.isBlank()) {
            throw new LedgerException.NotFound("sample consumer", "statistics");
        }
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.status(response.statusCode()).contentType(MediaType.APPLICATION_JSON)
                    .body(response.body());
        } catch (IOException e) {
            return ResponseEntity.status(502).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body("{\"type\":\"urn:ledger:problem:consumer-unreachable\",\"title\":\"Sample consumer unreachable\","
                            + "\"status\":502,\"detail\":\"the sample consumer did not answer\",\"code\":\"consumer-unreachable\"}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(503).build();
        }
    }

    /**
     * @param apiKey          the demo key, or {@code null} outside demo mode
     * @param consumerVisible whether the sample consumer's statistics are available
     */
    record UiConfig(boolean demo, boolean chaos, String apiKey, boolean consumerVisible) {
    }
}
