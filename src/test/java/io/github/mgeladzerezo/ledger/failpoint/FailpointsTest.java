package io.github.mgeladzerezo.ledger.failpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The registry itself, with the JVM halt replaced by a recorder. The real halt is exercised by FailpointCrashIT. */
class FailpointsTest {

    private final List<Integer> halts = new ArrayList<>();

    @Test
    void unarmedFailpointsDoNothing() {
        Failpoints failpoints = new Failpoints(Set.of(), halts::add);

        Arrays.stream(Failpoint.values()).forEach(failpoints::hit);

        assertThat(halts).isEmpty();
        assertThat(failpoints.armed()).isEmpty();
    }

    @Test
    void failpointArmedFromConfigurationHaltsWithTheDocumentedExitCode() {
        Failpoints failpoints = new Failpoints(Set.of("before-commit"), halts::add);

        failpoints.hit(Failpoint.AFTER_DEBIT_ENTRY);
        assertThat(halts).isEmpty();

        failpoints.hit(Failpoint.BEFORE_COMMIT);
        assertThat(halts).containsExactly(Failpoints.EXIT_CODE);
        assertThat(failpoints.armed()).containsExactly(Failpoint.BEFORE_COMMIT);
    }

    @Test
    void failpointsCanBeArmedAndDisarmedAtRunTime() {
        Failpoints failpoints = new Failpoints(Set.of(), halts::add);

        failpoints.arm(Failpoint.RELAY_AFTER_SEND_BEFORE_MARK);
        failpoints.hit(Failpoint.RELAY_AFTER_SEND_BEFORE_MARK);
        failpoints.disarmAll();
        failpoints.hit(Failpoint.RELAY_AFTER_SEND_BEFORE_MARK);

        assertThat(halts).hasSize(1);
    }

    @Test
    void unknownFailpointNameInConfigurationFailsFast() {
        assertThatThrownBy(() -> new Failpoints(Set.of("after-lunch"), halts::add))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("after-lunch");
    }

    @Test
    void everyFailpointHasAUniqueIdAndDocumentation() {
        assertThat(Arrays.stream(Failpoint.values()).map(Failpoint::id)).doesNotHaveDuplicates()
                .contains("after-debit-entry", "before-commit", "after-commit-before-response",
                        "after-outbox-insert", "relay-after-send-before-mark");
        assertThat(Failpoint.values()).allSatisfy(f -> {
            assertThat(f.state()).isNotBlank();
            assertThat(f.recovery()).isNotBlank();
            assertThat(Failpoint.byId(f.id())).contains(f);
        });
    }
}
