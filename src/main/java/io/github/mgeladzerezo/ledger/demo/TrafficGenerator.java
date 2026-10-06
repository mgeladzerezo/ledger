package io.github.mgeladzerezo.ledger.demo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import io.github.mgeladzerezo.ledger.core.Account;
import io.github.mgeladzerezo.ledger.core.AccountRepository;
import io.github.mgeladzerezo.ledger.core.Caller;
import io.github.mgeladzerezo.ledger.core.Hold;
import io.github.mgeladzerezo.ledger.core.HoldRepository;
import io.github.mgeladzerezo.ledger.core.HoldService;
import io.github.mgeladzerezo.ledger.core.JournalRepository;
import io.github.mgeladzerezo.ledger.core.JournalTransaction;
import io.github.mgeladzerezo.ledger.core.LedgerService;
import io.github.mgeladzerezo.ledger.domain.EntryLine;
import io.github.mgeladzerezo.ledger.domain.HoldStatus;
import io.github.mgeladzerezo.ledger.domain.TransactionKind;
import io.github.mgeladzerezo.ledger.idempotency.IdempotentExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background traffic for the demo dashboard, only with {@code ledger.demo.enabled=true}. Each
 * tick performs one random operation through the same idempotent path the HTTP API uses, so the
 * journal, the outbox and the reconciliation reports have something to show. Some operations are
 * expected to be rejected (an overdraft, a hold that is already captured); that is part of the
 * picture.
 */
@Component
@ConditionalOnProperty(name = "ledger.demo.enabled", havingValue = "true")
public class TrafficGenerator {

    private static final Logger log = LoggerFactory.getLogger(TrafficGenerator.class);
    private static final String PRINCIPAL = "demo-traffic";

    private final IdempotentExecutor executor;
    private final LedgerService ledger;
    private final HoldService holds;
    private final AccountRepository accounts;
    private final HoldRepository holdRepository;
    private final JournalRepository journal;

    public TrafficGenerator(IdempotentExecutor executor, LedgerService ledger, HoldService holds,
                            AccountRepository accounts, HoldRepository holdRepository, JournalRepository journal) {
        this.executor = executor;
        this.ledger = ledger;
        this.holds = holds;
        this.accounts = accounts;
        this.holdRepository = holdRepository;
        this.journal = journal;
    }

    @Scheduled(fixedDelayString = "${ledger.demo.traffic-interval:700ms}", initialDelayString = "3s")
    public void tick() {
        try {
            List<Account> parties = parties();
            if (parties.size() < 2) {
                return;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Account from = parties.get(random.nextInt(parties.size()));
            Account to = other(parties, from);
            int dice = random.nextInt(100);
            if (dice < 50) {
                long amount = random.nextLong(100, 15_000);
                run("demo transfer", c -> ledger.transfer(from.id(), to.id(), amount, "USD", "Payment", c));
            } else if (dice < 60) {
                run("demo fee split", c -> transferWithFee(from, to, random.nextLong(1_000, 20_000), c));
            } else if (dice < 70) {
                run("demo deposit", c -> ledger.deposit(from.id(), random.nextLong(5_000, 50_000), "USD", "Top-up", c));
            } else if (dice < 76) {
                run("demo withdrawal", c -> ledger.withdraw(from.id(), random.nextLong(1_000, 30_000), "USD", "Payout", c));
            } else if (dice < 86) {
                run("demo hold", c -> holds.place(from.id(), random.nextLong(500, 8_000), "USD", "Card authorisation",
                        Duration.ofSeconds(random.nextLong(45, 180)), c));
            } else if (dice < 94) {
                resolveHold(random, to);
            } else if (dice < 97) {
                reverseRecentTransfer();
            } else {
                fxTransfer(random);
            }
        } catch (RuntimeException e) {
            log.debug("demo traffic tick failed: {}", e.toString());
        }
    }

    /** A three-leg transaction: the payer pays the amount, the payee receives it minus a 1.5% fee. */
    private JournalTransaction transferWithFee(Account from, Account to, long amount, Caller caller) {
        long fee = Math.max(1, amount * 15 / 1000);
        long feesAccount = accounts.findByCode(DemoSeeder.FEES).orElseThrow().id();
        return ledger.postEntries("Payment with fee", List.of(
                EntryLine.debit(from.id(), amount, "USD"),
                EntryLine.credit(to.id(), amount - fee, "USD"),
                EntryLine.credit(feesAccount, fee, "USD")), caller);
    }

    private void resolveHold(ThreadLocalRandom random, Account merchant) {
        List<Hold> active = holdRepository.list(null, 50).stream()
                .filter(h -> h.status() == HoldStatus.ACTIVE && h.accountId() != merchant.id())
                .toList();
        if (active.isEmpty()) {
            return;
        }
        Hold hold = active.get(random.nextInt(active.size()));
        if (random.nextInt(4) == 0) {
            run("demo release", c -> holds.release(hold.id()));
        } else {
            Long amount = random.nextBoolean() ? null : Math.max(1, hold.amount() * random.nextLong(40, 100) / 100);
            run("demo capture", c -> holds.capture(hold.id(), amount, merchant.id(), "Card capture", c));
        }
    }

    private void reverseRecentTransfer() {
        journal.list(Long.MAX_VALUE, 20, null).stream()
                .filter(t -> t.kind() == TransactionKind.TRANSFER && t.reversedBy() == null)
                .findFirst()
                .ifPresent(t -> run("demo refund", c -> ledger.reverse(t.id(), "customer refund", c)));
    }

    private void fxTransfer(ThreadLocalRandom random) {
        Account usd = accounts.findByCode(DemoSeeder.USD_PARTIES.getFirst()).orElseThrow();
        Account eur = accounts.findByCode(DemoSeeder.EUR_WALLET).orElseThrow();
        long usdAmount = random.nextLong(1_000, 10_000);
        long eurAmount = Math.round(usdAmount * 0.92);
        if (random.nextBoolean()) {
            run("demo fx", c -> ledger.fxTransfer(usd.id(), eur.id(), usdAmount, "USD", eurAmount, "EUR",
                    "USD to EUR at 0.92", c));
        } else {
            run("demo fx", c -> ledger.fxTransfer(eur.id(), usd.id(), eurAmount, "EUR", usdAmount, "USD",
                    "EUR to USD at 1.087", c));
        }
    }

    private void run(String operation, Function<Caller, ?> action) {
        Caller caller = new Caller(PRINCIPAL, "demo-" + UUID.randomUUID());
        executor.execute(caller, operation, "", 201, () -> action.apply(caller));
    }

    private List<Account> parties() {
        List<Account> parties = new ArrayList<>();
        DemoSeeder.USD_PARTIES.forEach(code -> accounts.findByCode(code).ifPresent(parties::add));
        return parties;
    }

    private static Account other(List<Account> parties, Account excluded) {
        Account candidate;
        do {
            candidate = parties.get(ThreadLocalRandom.current().nextInt(parties.size()));
        } while (candidate.id() == excluded.id());
        return candidate;
    }
}
