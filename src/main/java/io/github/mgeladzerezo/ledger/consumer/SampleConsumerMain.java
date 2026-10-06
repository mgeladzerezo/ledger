package io.github.mgeladzerezo.ledger.consumer;

import java.util.concurrent.CountDownLatch;

import org.postgresql.ds.PGSimpleDataSource;

/**
 * Runs the {@link SampleConsumer} as its own process, configured from environment variables:
 * {@code CONSUMER_DB_URL}, {@code CONSUMER_DB_USER}, {@code CONSUMER_DB_PASSWORD},
 * {@code CONSUMER_SECRET}, and optionally {@code CONSUMER_PORT} (default 8080) and
 * {@code CONSUMER_FAILURE_RATE} (default 0).
 *
 * <p>From the application jar:
 * {@code java -cp ledger.jar -Dloader.main=io.github.mgeladzerezo.ledger.consumer.SampleConsumerMain
 * org.springframework.boot.loader.launch.PropertiesLauncher}
 */
public final class SampleConsumerMain {

    private SampleConsumerMain() {
    }

    public static void main(String[] args) throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(require("CONSUMER_DB_URL"));
        dataSource.setUser(require("CONSUMER_DB_USER"));
        dataSource.setPassword(require("CONSUMER_DB_PASSWORD"));
        int port = Integer.parseInt(System.getenv().getOrDefault("CONSUMER_PORT", "8080"));
        double failureRate = Double.parseDouble(System.getenv().getOrDefault("CONSUMER_FAILURE_RATE", "0"));

        SampleConsumer consumer = new SampleConsumer(dataSource, require("CONSUMER_SECRET"), port, failureRate).start();
        Runtime.getRuntime().addShutdownHook(new Thread(consumer::close));
        new CountDownLatch(1).await();
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("environment variable " + name + " is required");
        }
        return value;
    }
}
