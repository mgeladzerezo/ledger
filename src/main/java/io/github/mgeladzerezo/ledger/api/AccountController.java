package io.github.mgeladzerezo.ledger.api;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;

import io.github.mgeladzerezo.ledger.core.Account;
import io.github.mgeladzerezo.ledger.core.AccountRepository;
import io.github.mgeladzerezo.ledger.core.JournalRepository;
import io.github.mgeladzerezo.ledger.core.JournalRepository.AccountEntry;
import io.github.mgeladzerezo.ledger.core.LedgerService;
import io.github.mgeladzerezo.ledger.domain.AccountStatus;
import io.github.mgeladzerezo.ledger.domain.AccountType;
import io.github.mgeladzerezo.ledger.domain.LedgerException;
import io.github.mgeladzerezo.ledger.reporting.ReportService;
import io.github.mgeladzerezo.ledger.reporting.ReportService.Statement;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountRepository accounts;
    private final JournalRepository journal;
    private final LedgerService ledger;
    private final ReportService reports;
    private final IdempotentEndpoint idempotent;
    private final TransactionTemplate transaction;

    public AccountController(AccountRepository accounts, JournalRepository journal, LedgerService ledger,
                             ReportService reports, IdempotentEndpoint idempotent,
                             PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.journal = journal;
        this.ledger = ledger;
        this.reports = reports;
        this.idempotent = idempotent;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @PostMapping
    ResponseEntity<String> open(@RequestBody OpenAccountRequest body, HttpServletRequest request) {
        body.validate();
        return idempotent.run(request, body, 201,
                caller -> ledger.openAccount(body.code(), body.name(), body.type(), body.currency(),
                        Boolean.TRUE.equals(body.nonNegative())));
    }

    @GetMapping
    Page<Account> list(@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        int size = Page.limit(limit);
        return Page.of(accounts.list(Page.decode(cursor, 0), size + 1), size, Account::id);
    }

    @GetMapping("/{id}")
    Account get(@PathVariable long id) {
        return accounts.findById(id).orElseThrow(() -> new LedgerException.NotFound("account", id));
    }

    /** The account's entries, newest first, paginated by cursor. */
    @GetMapping("/{id}/entries")
    Page<AccountEntry> entries(@PathVariable long id, @RequestParam(required = false) String cursor,
                               @RequestParam(required = false) Integer limit) {
        get(id);
        int size = Page.limit(limit);
        List<AccountEntry> fetched = journal.entriesForAccount(id, Page.decode(cursor, Long.MAX_VALUE), size + 1);
        return Page.of(fetched, size, item -> item.entry().id());
    }

    /** Daily statement for a UTC calendar day; defaults to today. */
    @GetMapping("/{id}/statement")
    Statement statement(@PathVariable long id, @RequestParam(required = false) String date) {
        try {
            return reports.statement(id, date == null ? LocalDate.now(ZoneOffset.UTC) : LocalDate.parse(date));
        } catch (DateTimeParseException e) {
            throw new BadRequestException("date must be an ISO date such as 2026-01-31");
        }
    }

    @PostMapping("/{id}/status")
    ResponseEntity<String> changeStatus(@PathVariable long id, @RequestBody StatusRequest body,
                                        HttpServletRequest request) {
        BadRequestException.require(body.status() != null, "status is required");
        return idempotent.run(request, body, 200, caller -> ledger.changeStatus(id, body.status()));
    }

    /**
     * Recomputes the materialised balance from the journal. Not keyed by an idempotency key
     * because the operation is idempotent by nature: running it twice yields the same row.
     */
    @PostMapping("/{id}/rebuild-balance")
    Account rebuildBalance(@PathVariable long id) {
        return transaction.execute(status -> ledger.rebuildBalance(id));
    }

    /**
     * @param nonNegative if true the account's available balance may never drop below zero
     */
    record OpenAccountRequest(String code, String name, AccountType type, String currency, Boolean nonNegative) {
        void validate() {
            BadRequestException.require(code != null && code.matches("[A-Za-z0-9:_.-]{1,64}"),
                    "code is required: 1 to 64 characters from A-Z a-z 0-9 : _ . -");
            BadRequestException.require(!code.startsWith("system:"), "codes starting with 'system:' are reserved");
            BadRequestException.require(name != null && !name.isBlank() && name.length() <= 200,
                    "name is required, at most 200 characters");
            BadRequestException.require(type != null, "type is required");
            BadRequestException.require(currency != null && currency.matches("[A-Z]{3}"),
                    "currency must be a three-letter upper-case code");
        }
    }

    record StatusRequest(AccountStatus status) {
    }
}
