package io.github.mgeladzerezo.ledger.api;

import java.util.List;
import java.util.UUID;

import io.github.mgeladzerezo.ledger.core.JournalRepository;
import io.github.mgeladzerezo.ledger.core.JournalTransaction;
import io.github.mgeladzerezo.ledger.core.LedgerService;
import io.github.mgeladzerezo.ledger.domain.EntryLine;
import io.github.mgeladzerezo.ledger.domain.LedgerException;
import io.github.mgeladzerezo.ledger.domain.Side;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Money movements and the journal. Every POST here requires an {@code Idempotency-Key}. */
@RestController
@RequestMapping("/api/v1")
public class TransactionController {

    private final LedgerService ledger;
    private final JournalRepository journal;
    private final IdempotentEndpoint idempotent;

    public TransactionController(LedgerService ledger, JournalRepository journal, IdempotentEndpoint idempotent) {
        this.ledger = ledger;
        this.journal = journal;
        this.idempotent = idempotent;
    }

    @PostMapping("/deposits")
    ResponseEntity<String> deposit(@RequestBody CashRequest body, HttpServletRequest request) {
        body.validate();
        return idempotent.run(request, body, 201, caller -> ledger.deposit(body.accountId(), body.amount(),
                body.currency(), text(body.description(), "Deposit"), caller));
    }

    @PostMapping("/withdrawals")
    ResponseEntity<String> withdraw(@RequestBody CashRequest body, HttpServletRequest request) {
        body.validate();
        return idempotent.run(request, body, 201, caller -> ledger.withdraw(body.accountId(), body.amount(),
                body.currency(), text(body.description(), "Withdrawal"), caller));
    }

    @PostMapping("/transfers")
    ResponseEntity<String> transfer(@RequestBody TransferRequest body, HttpServletRequest request) {
        body.validate();
        return idempotent.run(request, body, 201, caller -> ledger.transfer(body.fromAccountId(),
                body.toAccountId(), body.amount(), body.currency(), text(body.description(), "Transfer"), caller));
    }

    @PostMapping("/fx-transfers")
    ResponseEntity<String> fxTransfer(@RequestBody FxTransferRequest body, HttpServletRequest request) {
        body.validate();
        return idempotent.run(request, body, 201, caller -> ledger.fxTransfer(body.fromAccountId(),
                body.toAccountId(), body.sourceAmount(), body.sourceCurrency(), body.targetAmount(),
                body.targetCurrency(), text(body.description(), "FX transfer"), caller));
    }

    /** A multi-leg transaction: any number of entries, as long as they balance per currency. */
    @PostMapping("/transactions")
    ResponseEntity<String> post(@RequestBody JournalRequest body, HttpServletRequest request) {
        body.validate();
        return idempotent.run(request, body, 201, caller -> ledger.postEntries(
                text(body.description(), "Journal entry"),
                body.entries().stream()
                        .map(e -> new EntryLine(e.accountId(), e.side(), e.amount(), e.currency()))
                        .toList(),
                caller));
    }

    @PostMapping("/transactions/{id}/reversals")
    ResponseEntity<String> reverse(@PathVariable UUID id, @RequestBody(required = false) ReversalRequest body,
                                   HttpServletRequest request) {
        ReversalRequest reversal = body == null ? new ReversalRequest(null) : body;
        return idempotent.run(request, reversal, 201, caller -> ledger.reverse(id, reversal.reason(), caller));
    }

    /** The journal, newest first, paginated by cursor. */
    @GetMapping("/transactions")
    Page<JournalTransaction> list(@RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) Long accountId) {
        int size = Page.limit(limit);
        List<JournalTransaction> fetched = journal.list(Page.decode(cursor, Long.MAX_VALUE), size + 1, accountId);
        return Page.of(fetched, size, JournalTransaction::seq);
    }

    @GetMapping("/transactions/{id}")
    JournalTransaction get(@PathVariable UUID id) {
        return journal.find(id).orElseThrow(() -> new LedgerException.NotFound("transaction", id));
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void requireAmount(Long amount, String field) {
        BadRequestException.require(amount != null && amount > 0,
                field + " is required and must be a positive integer of minor units");
    }

    private static void requireCurrency(String currency, String field) {
        BadRequestException.require(currency != null && currency.matches("[A-Z]{3}"),
                field + " must be a three-letter upper-case code");
    }

    private static void requireDescription(String description) {
        BadRequestException.require(description == null || description.length() <= 500,
                "description must be at most 500 characters");
    }

    record CashRequest(Long accountId, Long amount, String currency, String description) {
        void validate() {
            BadRequestException.require(accountId != null, "accountId is required");
            requireAmount(amount, "amount");
            requireCurrency(currency, "currency");
            requireDescription(description);
        }
    }

    record TransferRequest(Long fromAccountId, Long toAccountId, Long amount, String currency, String description) {
        void validate() {
            BadRequestException.require(fromAccountId != null && toAccountId != null,
                    "fromAccountId and toAccountId are required");
            BadRequestException.require(!fromAccountId.equals(toAccountId),
                    "fromAccountId and toAccountId must differ");
            requireAmount(amount, "amount");
            requireCurrency(currency, "currency");
            requireDescription(description);
        }
    }

    record FxTransferRequest(Long fromAccountId, Long toAccountId, Long sourceAmount, String sourceCurrency,
                             Long targetAmount, String targetCurrency, String description) {
        void validate() {
            BadRequestException.require(fromAccountId != null && toAccountId != null,
                    "fromAccountId and toAccountId are required");
            requireAmount(sourceAmount, "sourceAmount");
            requireAmount(targetAmount, "targetAmount");
            requireCurrency(sourceCurrency, "sourceCurrency");
            requireCurrency(targetCurrency, "targetCurrency");
            requireDescription(description);
        }
    }

    record JournalRequest(String description, List<Line> entries) {
        void validate() {
            BadRequestException.require(entries != null && !entries.isEmpty(), "entries are required");
            BadRequestException.require(entries.size() <= 100, "at most 100 entries per transaction");
            requireDescription(description);
            for (Line line : entries) {
                BadRequestException.require(line != null && line.accountId() != null && line.side() != null,
                        "every entry needs accountId and side");
                requireAmount(line.amount(), "entry amount");
                requireCurrency(line.currency(), "entry currency");
            }
        }

        record Line(Long accountId, Side side, Long amount, String currency) {
        }
    }

    record ReversalRequest(String reason) {
    }
}
