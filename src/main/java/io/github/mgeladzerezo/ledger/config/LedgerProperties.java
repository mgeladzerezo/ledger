package io.github.mgeladzerezo.ledger.config;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every tunable of the service, bound from the {@code ledger.*} namespace. Defaults are the
 * production-leaning ones; {@code docker-compose.yml} turns on the demo and chaos flags.
 */
@ConfigurationProperties("ledger")
public record LedgerProperties(
        @DefaultValue Security security,
        @DefaultValue Idempotency idempotency,
        @DefaultValue Posting posting,
        @DefaultValue Holds holds,
        @DefaultValue Outbox outbox,
        @DefaultValue Reconciliation reconciliation,
        @DefaultValue Failpoints failpoints,
        @DefaultValue Chaos chaos,
        @DefaultValue Demo demo) {

    /** @param apiKeys principal name to API key; a request authenticates by sending one of the keys */
    public record Security(Map<String, String> apiKeys) {
        public Security {
            apiKeys = apiKeys == null ? Map.of() : Map.copyOf(apiKeys);
        }
    }

    /**
     * @param retention     how long a key (and its stored response) is remembered
     * @param waitTimeout   how long a duplicate waits for the in-flight original before getting 409
     * @param purgeInterval how often expired keys are deleted
     */
    public record Idempotency(@DefaultValue("24h") Duration retention,
                              @DefaultValue("5s") Duration waitTimeout,
                              @DefaultValue("10m") Duration purgeInterval) {
    }

    /** @param lockTimeout upper bound on waiting for account row locks before answering 503 */
    public record Posting(@DefaultValue("10s") Duration lockTimeout) {
    }

    public record Holds(@DefaultValue("7d") Duration defaultTtl,
                        @DefaultValue("30s") Duration expiryInterval) {
    }

    /**
     * @param relayEnabled whether this instance runs the background relay loop
     * @param maxAttempts  delivery attempts before a delivery is parked as DEAD
     * @param backoffBase  delay after the first failure; doubles on each further failure
     * @param backoffMax   cap on the delay between attempts
     */
    public record Outbox(@DefaultValue("true") boolean relayEnabled,
                         @DefaultValue("500ms") Duration pollInterval,
                         @DefaultValue("20") int batchSize,
                         @DefaultValue("8") int maxAttempts,
                         @DefaultValue("1s") Duration backoffBase,
                         @DefaultValue("5m") Duration backoffMax,
                         @DefaultValue("5s") Duration httpTimeout) {
    }

    /**
     * @param scheduled        whether this instance runs the periodic job
     * @param stuckDeliveryAge a pending delivery older than this is reported as a warning
     * @param keepRuns         number of most recent reports retained
     */
    public record Reconciliation(@DefaultValue("true") boolean scheduled,
                                 @DefaultValue("60s") Duration interval,
                                 @DefaultValue("5m") Duration stuckDeliveryAge,
                                 @DefaultValue("200") int keepRuns) {
    }

    /** @param armed failpoint names armed at start-up; the process halts when one is reached */
    public record Failpoints(Set<String> armed) {
        public Failpoints {
            armed = armed == null ? Set.of() : Set.copyOf(armed);
        }
    }

    /** @param enabled allows failpoints to be armed over HTTP; never enable outside a demo or test */
    public record Chaos(@DefaultValue("false") boolean enabled) {
    }

    /**
     * @param enabled         seed demo accounts and run the background traffic generator
     * @param consumerUrl     webhook URL of the sample consumer to subscribe at start-up, if any
     * @param consumerSecret  shared HMAC secret for that subscription
     * @param consumerStatsUrl where the UI's "consumer" panel reads the sample consumer's counters
     */
    public record Demo(@DefaultValue("false") boolean enabled,
                       @DefaultValue("700ms") Duration trafficInterval,
                       String consumerUrl,
                       String consumerSecret,
                       String consumerStatsUrl) {
    }
}
