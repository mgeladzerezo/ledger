package io.github.mgeladzerezo.ledger.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Set;

import io.github.mgeladzerezo.ledger.domain.LedgerException;
import io.github.mgeladzerezo.ledger.outbox.OutboxRepository;
import io.github.mgeladzerezo.ledger.outbox.OutboxRepository.DeliveryView;
import io.github.mgeladzerezo.ledger.outbox.OutboxRepository.EventSummary;
import io.github.mgeladzerezo.ledger.outbox.OutboxRepository.OutboxStats;
import io.github.mgeladzerezo.ledger.outbox.OutboxRepository.Subscription;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Webhook subscriptions and a window onto the outbox: events, deliveries, retries, dead letters. */
@RestController
@RequestMapping("/api/v1")
public class OutboxController {

    private static final Set<String> STATUSES = Set.of("PENDING", "SENT", "DEAD");

    private final OutboxRepository outbox;
    private final IdempotentEndpoint idempotent;

    public OutboxController(OutboxRepository outbox, IdempotentEndpoint idempotent) {
        this.outbox = outbox;
        this.idempotent = idempotent;
    }

    @PostMapping("/webhooks/subscriptions")
    ResponseEntity<String> subscribe(@RequestBody SubscriptionRequest body, HttpServletRequest request) {
        body.validate();
        return idempotent.run(request, body, 201, caller -> outbox.insertSubscription(body.url(), body.secret()));
    }

    @GetMapping("/webhooks/subscriptions")
    List<Subscription> subscriptions() {
        return outbox.listSubscriptions();
    }

    /** Deactivates the subscription: pending deliveries stop, history is kept. Idempotent by nature. */
    @DeleteMapping("/webhooks/subscriptions/{id}")
    ResponseEntity<Void> unsubscribe(@PathVariable long id) {
        if (!outbox.deactivateSubscription(id)) {
            throw new LedgerException.NotFound("subscription", id);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/outbox/events")
    Page<EventSummary> events(@RequestParam(required = false) String cursor,
                              @RequestParam(required = false) Integer limit) {
        int size = Page.limit(limit);
        return Page.of(outbox.listEvents(Page.decode(cursor, Long.MAX_VALUE), size + 1), size, EventSummary::sequence);
    }

    @GetMapping("/outbox/deliveries")
    Page<DeliveryView> deliveries(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer limit) {
        BadRequestException.require(status == null || STATUSES.contains(status),
                "status must be one of " + STATUSES);
        int size = Page.limit(limit);
        return Page.of(outbox.listDeliveries(status, Page.decode(cursor, Long.MAX_VALUE), size + 1), size,
                DeliveryView::id);
    }

    @GetMapping("/outbox/stats")
    OutboxStats stats() {
        return outbox.stats();
    }

    /** Puts a dead-lettered delivery back in the queue. Idempotent by nature. */
    @PostMapping("/outbox/deliveries/{id}/retry")
    ResponseEntity<Void> retry(@PathVariable long id) {
        if (!outbox.requeue(id)) {
            throw new LedgerException.Conflict("delivery-not-retryable", "Delivery cannot be retried",
                    "delivery " + id + " does not exist or was already sent");
        }
        return ResponseEntity.accepted().build();
    }

    record SubscriptionRequest(String url, String secret) {
        void validate() {
            BadRequestException.require(url != null && secret != null, "url and secret are required");
            BadRequestException.require(secret.length() >= 16 && secret.length() <= 200,
                    "secret must be 16 to 200 characters");
            try {
                URI uri = new URI(url);
                BadRequestException.require(
                        ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null,
                        "url must be an absolute http or https URL");
            } catch (URISyntaxException e) {
                throw new BadRequestException("url must be an absolute http or https URL");
            }
        }
    }
}
