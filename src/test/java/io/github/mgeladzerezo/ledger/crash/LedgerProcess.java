package io.github.mgeladzerezo.ledger.crash;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.mgeladzerezo.ledger.support.Api;
import io.github.mgeladzerezo.ledger.support.SharedPostgres;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The application running as its own operating-system process, started from the repackaged jar
 * exactly as it would be in production ({@code java -jar}). The crash tests need a real process:
 * {@code Runtime.halt()} and {@code destroyForcibly()} would take an in-process test down with
 * them, and only a separate JVM loses its sockets, threads and connection pool the way a crashed
 * server does.
 */
final class LedgerProcess {

    static final String API_KEY = "crash-test-key";

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final Process process;
    private final int port;
    private final Path log;

    private LedgerProcess(Process process, int port, Path log) {
        this.process = process;
        this.port = port;
        this.log = log;
    }

    /**
     * Starts the jar against the shared Testcontainers PostgreSQL and waits until it reports healthy.
     *
     * @param extraArgs additional {@code --property=value} arguments, e.g. to arm a failpoint at start-up
     */
    static LedgerProcess start(String... extraArgs) {
        Path jar = Path.of(System.getProperty("ledger.jar", "target/ledger-1.0.0.jar"));
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("application jar not found at " + jar.toAbsolutePath()
                    + "; the crash tests run in the integration-test phase, after `package`");
        }
        PostgreSQLContainer postgres = SharedPostgres.instance();
        int port = freePort();
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m",
                "-XX:TieredStopAtLevel=1",
                "-jar", jar.toAbsolutePath().toString(),
                "--server.port=" + port,
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--ledger.security.api-keys.crash=" + API_KEY,
                "--ledger.chaos.enabled=true",
                "--ledger.outbox.poll-interval=100ms",
                "--ledger.outbox.backoff-base=200ms",
                "--ledger.outbox.backoff-max=1s",
                "--ledger.outbox.max-attempts=50",
                "--ledger.reconciliation.scheduled=false",
                "--server.shutdown=immediate",
                "--logging.level.root=WARN"));
        command.addAll(List.of(extraArgs));
        try {
            Path logDirectory = Path.of(System.getProperty("ledger.crash.logs", "target/crash-it"));
            Files.createDirectories(logDirectory);
            Path log = logDirectory.resolve("ledger-" + COUNTER.incrementAndGet() + ".log");
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .directory(new File(System.getProperty("java.io.tmpdir")))
                    .start();
            LedgerProcess ledger = new LedgerProcess(process, port, log);
            ledger.awaitHealthy(Duration.ofSeconds(120));
            return ledger;
        } catch (IOException e) {
            throw new IllegalStateException("could not start the application process", e);
        }
    }

    Api api() {
        return new Api("http://localhost:" + port, API_KEY);
    }

    boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Waits for the process to die on its own.
     *
     * @return its exit status
     */
    int awaitExit(Duration timeout) {
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("the application process is still alive after " + timeout + "\n" + logTail());
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** SIGKILL / TerminateProcess: no shutdown hooks, no graceful anything. */
    void kill() {
        process.destroyForcibly();
        try {
            process.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitHealthy(Duration timeout) {
        Api api = api();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("the application exited during start-up with status "
                        + process.exitValue() + "\n" + logTail());
            }
            try {
                if (api.get("/actuator/health").status() == 200) {
                    return;
                }
            } catch (Api.ConnectionFailed notYet) {
                // the port is not open yet
            }
            sleep(150);
        }
        kill();
        throw new IllegalStateException("the application did not become healthy within " + timeout + "\n" + logTail());
    }

    private String logTail() {
        try {
            List<String> lines = Files.readAllLines(log);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
        } catch (IOException e) {
            return "(no log: " + e.getMessage() + ")";
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
