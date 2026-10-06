package io.github.mgeladzerezo.ledger.failpoint;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.IntConsumer;

import io.github.mgeladzerezo.ledger.config.LedgerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Registry of armed {@link Failpoint}s. Reaching an armed failpoint calls
 * {@link Runtime#halt(int)}: no shutdown hooks, no connection-pool close, no flushing. To the
 * database this is indistinguishable from pulling the plug on the application host, which is the
 * failure the crash tests need to reproduce.
 *
 * <p>Failpoints are armed from {@code ledger.failpoints.armed} at start-up, or at run time through
 * the chaos API, which only exists when {@code ledger.chaos.enabled=true}.
 */
@Component
public class Failpoints {

    /** Exit status of a process killed by a failpoint; the crash tests assert on it. */
    public static final int EXIT_CODE = 137;

    private static final Logger log = LoggerFactory.getLogger(Failpoints.class);

    private final Set<Failpoint> armed = java.util.Collections.synchronizedSet(EnumSet.noneOf(Failpoint.class));
    private final IntConsumer halt;

    @Autowired
    public Failpoints(LedgerProperties properties) {
        this(properties.failpoints().armed(), status -> Runtime.getRuntime().halt(status));
    }

    Failpoints(Set<String> armedAtStartup, IntConsumer halt) {
        this.halt = halt;
        for (String id : armedAtStartup) {
            arm(Failpoint.byId(id).orElseThrow(() -> new IllegalArgumentException("unknown failpoint '" + id + "'")));
        }
    }

    /** Dies here if {@code failpoint} is armed; otherwise costs one set lookup. */
    public void hit(Failpoint failpoint) {
        if (armed.contains(failpoint)) {
            log.error("FAILPOINT {} reached: halting the JVM without cleanup", failpoint.id());
            halt.accept(EXIT_CODE);
        }
    }

    public void arm(Failpoint failpoint) {
        armed.add(failpoint);
        log.warn("failpoint {} armed: the process will halt when it is reached", failpoint.id());
    }

    public void disarmAll() {
        armed.clear();
    }

    public Set<Failpoint> armed() {
        synchronized (armed) {
            return armed.isEmpty() ? EnumSet.noneOf(Failpoint.class) : EnumSet.copyOf(armed);
        }
    }
}
