package com.tj.crypto.trading.venue.fencing;

import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.trading.venue.PrivateTradingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.Objects;
import java.util.UUID;

/**
 * Database-backed single-writer gate for every private venue mutation.
 *
 * <p>A new owner receives a monotonically increasing fencing token. A takeover deliberately
 * moves the durable kill switch to HALT, so recovery/reconciliation and an explicit operator
 * decision must happen before risk can be increased again.</p>
 */
@Slf4j
@Service
public class ExecutionWriterLeaseService {
    static final String GLOBAL_SCOPE = "GLOBAL_PRIVATE_VENUE_WRITER";

    private final ExecutionWriterLeaseMapper mapper;
    private final PrivateTradingProperties properties;
    private final KillSwitch killSwitch;
    private final String ownerId;

    private volatile long ownedToken;
    private volatile boolean previouslyOwned;

    @Autowired
    public ExecutionWriterLeaseService(ExecutionWriterLeaseMapper mapper,
                                       PrivateTradingProperties properties,
                                       KillSwitch killSwitch) {
        this(mapper, properties, killSwitch, processIdentity());
    }

    ExecutionWriterLeaseService(ExecutionWriterLeaseMapper mapper,
                                PrivateTradingProperties properties,
                                KillSwitch killSwitch,
                                String ownerId) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.killSwitch = Objects.requireNonNull(killSwitch, "killSwitch");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
    }

    /** Called immediately before a venue place/cancel mutation. */
    public synchronized long requireOwnership() {
        LeaseResult result = acquireOrRenew();
        if (!result.owned()) {
            throw new IllegalStateException("Private venue write denied: another execution writer owns the lease");
        }
        if (result.takeover()) {
            haltForTakeover(result.token());
            throw new IllegalStateException(
                    "Execution writer ownership changed; reconciliation and explicit kill-switch release are required");
        }
        return result.token();
    }

    /** Keeps an already-owned lease alive. A standby does not contend until a write is requested. */
    @Scheduled(fixedDelayString = "${crypto.private-trading.writer-lease-renew-interval-ms:5000}")
    public synchronized void renewOwnedLease() {
        if (!previouslyOwned) return;
        LeaseResult result;
        try {
            result = acquireOrRenew();
        } catch (RuntimeException e) {
            loseOwnership("EXECUTION_WRITER_LEASE_DATABASE_FAILURE", e);
            return;
        }
        if (!result.owned()) {
            loseOwnership("EXECUTION_WRITER_LEASE_LOST", null);
        }
    }

    public LeaseStatus status() {
        return new LeaseStatus(ownerId, ownedToken, previouslyOwned);
    }

    private LeaseResult acquireOrRenew() {
        mapper.ensureExists(GLOBAL_SCOPE);
        int changed = mapper.tryAcquireOrRenew(GLOBAL_SCOPE, ownerId,
                properties.getWriterLeaseTtlMs());
        ExecutionWriterLeaseRow row = mapper.selectState(GLOBAL_SCOPE);
        boolean owned = changed == 1 && row != null
                && ownerId.equals(row.getOwnerId())
                && row.getLeaseUntilMs() > row.getDatabaseNowMs();
        if (!owned) return new LeaseResult(false, 0, false);

        long priorToken = ownedToken;
        ownedToken = row.getFencingToken();
        previouslyOwned = true;
        boolean takeover = priorToken == 0 && row.getFencingToken() > 1;
        log.debug("Execution writer lease held: owner={}, token={}, until={}",
                ownerId, row.getFencingToken(), row.getLeaseUntilMs());
        return new LeaseResult(true, row.getFencingToken(), takeover);
    }

    private void haltForTakeover(long token) {
        killSwitch.activate(KillSwitch.Mode.HALT,
                "EXECUTION_WRITER_TAKEOVER_TOKEN_" + token, ownerId);
        log.error("Execution writer takeover is fenced pending reconciliation: owner={}, token={}",
                ownerId, token);
    }

    private void loseOwnership(String reason, RuntimeException cause) {
        previouslyOwned = false;
        ownedToken = 0;
        try {
            killSwitch.activate(KillSwitch.Mode.HALT, reason, ownerId);
        } catch (RuntimeException persistenceFailure) {
            if (cause != null) persistenceFailure.addSuppressed(cause);
            log.error("Writer lease loss forced local HALT but durable transition failed", persistenceFailure);
            return;
        }
        if (cause == null) log.error("Writer lease lost; durable trading state is HALT");
        else log.error("Writer lease heartbeat failed; durable trading state is HALT", cause);
    }

    private static String processIdentity() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            host = "unknown-host";
        }
        String process = ManagementFactory.getRuntimeMXBean().getName();
        return (host + ":" + process + ":" + UUID.randomUUID()).substring(0,
                Math.min(160, host.length() + process.length() + 38));
    }

    private record LeaseResult(boolean owned, long token, boolean takeover) {}

    public record LeaseStatus(String ownerId, long fencingToken, boolean locallyOwned) {}
}
