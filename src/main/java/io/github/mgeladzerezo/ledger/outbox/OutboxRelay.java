package io.github.mgeladzerezo.ledger.outbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import io.github.mgeladzerezo.ledger.config.LedgerProperties;
import io.github.mgeladzerezo.ledger.failpoint.Failpoint;
import io.github.mgeladzerezo.ledger.failpoint.Failpoints;
import io.github.mgeladzerezo.ledger.outbox.OutboxRepository.ClaimedDelivery;
import io.github.mgeladzerezo.ledger.outbox.WebhookSender.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The read half of the transactional outbox: claims due deliveries, sends them, records the
 * outcome.
 *
 * <p>One cycle is one database transaction: claim a batch with {@code FOR UPDATE SKIP LOCKED},
 * send every delivery of the batch concurrently on virtual threads, write SENT / retry / DEAD,
 * commit. The row locks are the claim. If the process dies anywhere in the cycle, PostgreSQL
 * rolls the transaction back and the deliveries are simply due again, which is why delivery is
 * <em>at-least-once</em>: a crash after the subscriber answered but before the commit resends the
 * event. Subscribers deduplicate on the event id.
 *
 * <p>Holding a transaction open across HTTP calls is a deliberate trade: it keeps one pooled
 * connection busy for at most one HTTP timeout per cycle, and in exchange there is no lease
 * column, no lease expiry and no clock comparison to get wrong.
 */
@Component
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final WebhookSender sender;
    private final Failpoints failpoints;
    private final TransactionTemplate transaction;
    private final LedgerProperties.Outbox config;
    private final BackoffPolicy backoff;

    private volatile Thread worker;

    public OutboxRelay(OutboxRepository outbox, WebhookSender sender, Failpoints failpoints,
                       PlatformTransactionManager transactionManager, LedgerProperties properties) {
        this.outbox = outbox;
        this.sender = sender;
        this.failpoints = failpoints;
        this.transaction = new TransactionTemplate(transactionManager);
        this.config = properties.outbox();
        this.backoff = new BackoffPolicy(config.backoffBase(), config.backoffMax());
    }

    /**
     * Runs one claim-send-mark cycle.
     *
     * @return the number of deliveries attempted; less than the batch size means the queue is drained
     */
    public int runOnce() {
        Integer attempted = transaction.execute(status -> {
            List<ClaimedDelivery> batch = outbox.claimDue(config.batchSize());
            if (batch.isEmpty()) {
                return 0;
            }
            failpoints.hit(Failpoint.RELAY_BEFORE_SEND);
            List<SendResult> results = sendAll(batch);
            failpoints.hit(Failpoint.RELAY_AFTER_SEND_BEFORE_MARK);
            for (int i = 0; i < batch.size(); i++) {
                recordOutcome(batch.get(i), results.get(i));
            }
            failpoints.hit(Failpoint.RELAY_AFTER_MARK_BEFORE_COMMIT);
            return batch.size();
        });
        return attempted == null ? 0 : attempted;
    }

    private List<SendResult> sendAll(List<ClaimedDelivery> batch) {
        List<SendResult> results = new ArrayList<>(batch.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<SendResult>> pending = batch.stream()
                    .map(delivery -> executor.submit(() -> sender.send(delivery)))
                    .toList();
            for (Future<SendResult> future : pending) {
                results.add(await(future));
            }
        }
        return results;
    }

    private static SendResult await(Future<SendResult> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SendResult(false, null, "interrupted");
        } catch (ExecutionException e) {
            return new SendResult(false, null, String.valueOf(e.getCause()));
        }
    }

    private void recordOutcome(ClaimedDelivery delivery, SendResult result) {
        if (result.success()) {
            outbox.markSent(delivery.id(), result.httpStatus());
            return;
        }
        int failedAttempts = delivery.attempts() + 1;
        Duration retryIn = failedAttempts >= config.maxAttempts()
                ? null
                : backoff.delay(failedAttempts, ThreadLocalRandom.current().nextDouble());
        outbox.markFailed(delivery.id(), result.httpStatus(), result.error(), retryIn);
        if (retryIn == null) {
            log.warn("delivery {} of event {} dead-lettered after {} attempts: {}", delivery.id(),
                    delivery.eventId(), failedAttempts, result.error());
        }
    }

    @Override
    public void start() {
        if (!config.relayEnabled() || worker != null) {
            return;
        }
        worker = Thread.ofPlatform().name("outbox-relay").daemon(true).start(this::loop);
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (runOnce() < config.batchSize()) {
                    Thread.sleep(config.pollInterval());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.warn("outbox relay cycle failed, retrying after the poll interval: {}", e.toString());
                try {
                    Thread.sleep(config.pollInterval());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Override
    public void stop() {
        Thread running = worker;
        worker = null;
        if (running != null) {
            running.interrupt();
            try {
                running.join(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return worker != null;
    }
}
