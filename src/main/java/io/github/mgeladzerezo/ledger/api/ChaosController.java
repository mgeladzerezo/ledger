package io.github.mgeladzerezo.ledger.api;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import io.github.mgeladzerezo.ledger.config.LedgerProperties;
import io.github.mgeladzerezo.ledger.domain.LedgerException;
import io.github.mgeladzerezo.ledger.failpoint.Failpoint;
import io.github.mgeladzerezo.ledger.failpoint.Failpoints;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lists the failpoints and, only when {@code ledger.chaos.enabled=true}, arms them. Arming makes
 * the process kill itself the next time the failpoint is reached, so the flag is off by default
 * and the endpoint answers 403 without it: a production deployment cannot be crashed over HTTP.
 */
@RestController
@RequestMapping("/api/v1/chaos")
public class ChaosController {

    private final Failpoints failpoints;
    private final boolean enabled;

    public ChaosController(Failpoints failpoints, LedgerProperties properties) {
        this.failpoints = failpoints;
        this.enabled = properties.chaos().enabled();
    }

    @GetMapping("/failpoints")
    ChaosState list() {
        Set<Failpoint> armed = failpoints.armed();
        List<FailpointView> views = Arrays.stream(Failpoint.values())
                .map(f -> new FailpointView(f.id(), f.state(), f.recovery(), armed.contains(f)))
                .toList();
        return new ChaosState(enabled, views);
    }

    @PostMapping("/failpoints/{id}/arm")
    ChaosState arm(@PathVariable String id) {
        requireEnabled();
        failpoints.arm(Failpoint.byId(id).orElseThrow(() -> new LedgerException.NotFound("failpoint", id)));
        return list();
    }

    @PostMapping("/failpoints/disarm")
    ChaosState disarm() {
        requireEnabled();
        failpoints.disarmAll();
        return list();
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ChaosDisabledException();
        }
    }

    /** @param enabled whether arming over HTTP is allowed on this instance */
    record ChaosState(boolean enabled, List<FailpointView> failpoints) {
    }

    record FailpointView(String id, String state, String recovery, boolean armed) {
    }

    static class ChaosDisabledException extends RuntimeException {
    }
}
