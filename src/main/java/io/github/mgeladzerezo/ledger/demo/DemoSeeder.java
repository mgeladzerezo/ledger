package io.github.mgeladzerezo.ledger.demo;

import java.util.List;

import io.github.mgeladzerezo.ledger.config.LedgerProperties;
import io.github.mgeladzerezo.ledger.core.Account;
import io.github.mgeladzerezo.ledger.core.AccountRepository;
import io.github.mgeladzerezo.ledger.core.Caller;
import io.github.mgeladzerezo.ledger.core.LedgerService;
import io.github.mgeladzerezo.ledger.domain.AccountType;
import io.github.mgeladzerezo.ledger.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Demo data, only with {@code ledger.demo.enabled=true}: a handful of customer, merchant and
 * revenue accounts with opening deposits, and a webhook subscription for the sample consumer.
 * Everything is created in one transaction and skipped when it already exists, so restarting the
 * demo (or crashing it from the chaos panel) never seeds twice.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "ledger.demo.enabled", havingValue = "true")
public class DemoSeeder implements ApplicationRunner {

    static final List<String> USD_PARTIES = List.of("cust:alice", "cust:bob", "cust:carol", "cust:dave",
            "merchant:coffee", "merchant:books");
    static final String FEES = "revenue:fees";
    static final String EUR_WALLET = "cust:alice:eur";

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);
    private static final Caller SEED = Caller.system("demo-seed");

    private final LedgerService ledger;
    private final AccountRepository accounts;
    private final OutboxRepository outbox;
    private final TransactionTemplate transaction;
    private final LedgerProperties.Demo demo;

    public DemoSeeder(LedgerService ledger, AccountRepository accounts, OutboxRepository outbox,
                      PlatformTransactionManager transactionManager, LedgerProperties properties) {
        this.ledger = ledger;
        this.accounts = accounts;
        this.outbox = outbox;
        this.transaction = new TransactionTemplate(transactionManager);
        this.demo = properties.demo();
    }

    @Override
    public void run(ApplicationArguments args) {
        subscribeSampleConsumer();
        if (accounts.findByCode(USD_PARTIES.getFirst()).isPresent()) {
            return;
        }
        transaction.executeWithoutResult(status -> {
            String[] names = {"Alice", "Bob", "Carol", "Dave", "Coffee shop (merchant)", "Bookshop (merchant)"};
            long[] openingDeposits = {250_000, 180_000, 320_000, 90_000, 0, 0};
            for (int i = 0; i < USD_PARTIES.size(); i++) {
                Account account = ledger.openAccount(USD_PARTIES.get(i), names[i], AccountType.LIABILITY, "USD", true);
                if (openingDeposits[i] > 0) {
                    ledger.deposit(account.id(), openingDeposits[i], "USD", "Opening deposit", SEED);
                }
            }
            ledger.openAccount(FEES, "Transfer fees", AccountType.REVENUE, "USD", false);
            Account eur = ledger.openAccount(EUR_WALLET, "Alice (EUR wallet)", AccountType.LIABILITY, "EUR", true);
            ledger.deposit(eur.id(), 50_000, "EUR", "Opening deposit", SEED);
        });
        log.info("demo data seeded");
    }

    private void subscribeSampleConsumer() {
        String url = demo.consumerUrl();
        if (url == null || url.isBlank() || demo.consumerSecret() == null) {
            return;
        }
        boolean present = outbox.listSubscriptions().stream().anyMatch(s -> s.url().equals(url) && s.active());
        if (!present) {
            outbox.insertSubscription(url, demo.consumerSecret());
            log.info("subscribed the sample consumer at {}", url);
        }
    }
}
