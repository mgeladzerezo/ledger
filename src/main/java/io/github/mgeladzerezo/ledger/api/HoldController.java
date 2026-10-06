package io.github.mgeladzerezo.ledger.api;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import io.github.mgeladzerezo.ledger.core.Hold;
import io.github.mgeladzerezo.ledger.core.HoldRepository;
import io.github.mgeladzerezo.ledger.core.HoldService;
import io.github.mgeladzerezo.ledger.domain.LedgerException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Two-phase payments: place a hold, then capture or release it. */
@RestController
@RequestMapping("/api/v1/holds")
public class HoldController {

    private static final long MAX_TTL_SECONDS = Duration.ofDays(30).toSeconds();

    private final HoldService holds;
    private final HoldRepository repository;
    private final IdempotentEndpoint idempotent;

    public HoldController(HoldService holds, HoldRepository repository, IdempotentEndpoint idempotent) {
        this.holds = holds;
        this.repository = repository;
        this.idempotent = idempotent;
    }

    @PostMapping
    ResponseEntity<String> place(@RequestBody PlaceHoldRequest body, HttpServletRequest request) {
        body.validate();
        Duration ttl = body.expiresInSeconds() == null ? null : Duration.ofSeconds(body.expiresInSeconds());
        return idempotent.run(request, body, 201, caller -> holds.place(body.accountId(), body.amount(),
                body.currency(), body.description(), ttl, caller));
    }

    @PostMapping("/{id}/capture")
    ResponseEntity<String> capture(@PathVariable UUID id, @RequestBody CaptureRequest body,
                                   HttpServletRequest request) {
        BadRequestException.require(body.toAccountId() != null, "toAccountId is required");
        BadRequestException.require(body.amount() == null || body.amount() > 0, "amount must be positive when given");
        return idempotent.run(request, body, 201, caller -> holds.capture(id, body.amount(), body.toAccountId(),
                body.description(), caller));
    }

    @PostMapping("/{id}/release")
    ResponseEntity<String> release(@PathVariable UUID id, HttpServletRequest request) {
        return idempotent.run(request, "", 200, caller -> holds.release(id));
    }

    @GetMapping
    List<Hold> list(@RequestParam(required = false) Long accountId, @RequestParam(required = false) Integer limit) {
        return repository.list(accountId, Page.limit(limit));
    }

    @GetMapping("/{id}")
    Hold get(@PathVariable UUID id) {
        return repository.find(id).orElseThrow(() -> new LedgerException.NotFound("hold", id));
    }

    /** @param expiresInSeconds lifetime of the reservation; the configured default applies when absent */
    record PlaceHoldRequest(Long accountId, Long amount, String currency, String description, Long expiresInSeconds) {
        void validate() {
            BadRequestException.require(accountId != null, "accountId is required");
            BadRequestException.require(amount != null && amount > 0,
                    "amount is required and must be a positive integer of minor units");
            BadRequestException.require(currency != null && currency.matches("[A-Z]{3}"),
                    "currency must be a three-letter upper-case code");
            BadRequestException.require(expiresInSeconds == null
                            || (expiresInSeconds > 0 && expiresInSeconds <= MAX_TTL_SECONDS),
                    "expiresInSeconds must be between 1 and " + MAX_TTL_SECONDS);
            BadRequestException.require(description == null || description.length() <= 500,
                    "description must be at most 500 characters");
        }
    }

    /** @param amount amount to capture; the full hold when absent */
    record CaptureRequest(Long amount, Long toAccountId, String description) {
    }
}
