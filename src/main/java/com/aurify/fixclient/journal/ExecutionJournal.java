package com.aurify.fixclient.journal;

import com.aurify.fixclient.canonical.enums.CanonicalExecType;
import com.aurify.fixclient.canonical.enums.CanonicalOrdStatus;
import com.aurify.fixclient.canonical.event.CanonicalExecutionReport;
import com.aurify.fixclient.canonical.event.CanonicalReject;
import com.aurify.fixclient.config.ExecutionJournalProperties;
import com.aurify.fixclient.pipeline.InboundEvent;
import com.aurify.fixclient.session.LpSessionEntry;
import com.aurify.fixclient.session.LpSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A short-lived record of every execution report, so nothing the venue said
 * about an order is lost between the report arriving and the caller storing it.
 *
 * SubmitMarketOrder answers with what was known when the RPC completed. An LP
 * keeps talking after that - further partial fills, the eventual resolution of
 * an order that timed out - and previously those reports reached only a ring
 * buffer of log strings, so the caller's record of an order froze at whatever
 * the RPC happened to return.
 *
 * This is a replay buffer, not a store: it is bounded and it does not survive a
 * restart, because the durable execution ledger belongs to the caller. What it
 * guarantees is a window in which a subscriber that reconnects, or a caller
 * backfilling one order, can still collect what it missed.
 */
@Slf4j
@Component
public class ExecutionJournal {

    private final ExecutionJournalProperties properties;
    private final LpSessionRegistry lpSessionRegistry;

    /** Per-order history, for GetOrderExecutions and for ordering within an order. */
    private final Map<String, Deque<JournaledReport>> byClOrdId = new ConcurrentHashMap<>();

    /** Arrival-ordered history, for replaying the gap to a reconnecting subscriber. */
    private final Deque<JournaledReport> chronological = new ConcurrentLinkedDeque<>();

    /**
     * Fan-out to subscribers, with the replay window built in.
     *
     * A replay sink rather than a plain multicast one: composing a snapshot with
     * a live feed leaves a window between taking the snapshot and subscribing in
     * which a report reaches neither, and a report lost there is lost for good.
     * Letting the sink hold the history closes that window - a late subscriber
     * is delivered the buffered reports and the live ones as one ordered
     * sequence. Bounded by both count and age, for the same reason the rest of
     * the journal is: this is a replay window, not a store.
     */
    private final Sinks.Many<JournaledReport> live;

    public ExecutionJournal(ExecutionJournalProperties properties, LpSessionRegistry lpSessionRegistry) {
        this.properties = properties;
        this.lpSessionRegistry = lpSessionRegistry;
        this.live = Sinks.many().replay().limit(
                properties.getMaxReports(),
                Duration.ofMinutes(properties.getRetentionMinutes()));
    }

    /** Every report the gateway has seen for this order, oldest first. */
    public List<JournaledReport> reportsFor(String clOrdId) {
        Deque<JournaledReport> reports = byClOrdId.get(clOrdId);
        return reports == null ? List.of() : List.copyOf(reports);
    }

    /**
     * Buffered reports from {@code since}, then live ones, as one sequence.
     *
     * A null {@code since} means live only, resolved per subscriber at
     * subscribe time so an existing buffer is not replayed to a caller that
     * asked for nothing before now.
     */
    public Flux<JournaledReport> stream(Instant since) {
        return Flux.defer(() -> {
            Instant from = since != null ? since : Instant.now();
            return live.asFlux().filter(report -> !report.receivedAt().isBefore(from));
        });
    }

    /**
     * Recorded from the envelope rather than the bare canonical event: the LP
     * account and the raw FIX both come from the transport facts the canonical
     * DTO deliberately does not carry. The pipeline publishes the envelope
     * before the bare event, so a report is journalled before the gRPC call it
     * settles is allowed to answer.
     */
    @EventListener
    public void onInboundEvent(InboundEvent inbound) {
        if (inbound.event() instanceof CanonicalExecutionReport report) {
            record(new JournaledReport(
                    attribute(inbound, report.getClOrdId()), report, inbound.rawFix(), Instant.now()));
        } else if (inbound.event() instanceof CanonicalReject reject) {
            recordReject(inbound, reject);
        }
    }

    /**
     * A reject that names an order is the venue's final word on it, but it
     * arrives as a Reject rather than an ExecutionReport, so it would otherwise
     * never reach the caller's ledger. Recorded as a synthetic REJECTED report
     * for exactly that reason.
     */
    private void recordReject(InboundEvent inbound, CanonicalReject reject) {
        if (reject.getRefId() == null) {
            return; // session-level reject: names a sequence number, not an order
        }
        CanonicalExecutionReport synthetic = CanonicalExecutionReport.builder()
                .provider(reject.getProvider())
                .clOrdId(reject.getRefId())
                .execType(CanonicalExecType.REJECTED)
                .ordStatus(CanonicalOrdStatus.REJECTED)
                .rejectText(reject.getText())
                .occurredAt(reject.occurredAt())
                .build();
        record(new JournaledReport(
                attribute(inbound, reject.getRefId()), synthetic, inbound.rawFix(), Instant.now()));
    }

    private String attribute(InboundEvent inbound, String clOrdId) {
        String lpAccountId = lpSessionRegistry.findBySessionId(inbound.sessionId())
                .map(LpSessionEntry::lpAccountId)
                .orElse(null);
        if (lpAccountId == null) {
            // A report arrived on a session we have no registry entry for. Record
            // it anyway - an unattributed report is still evidence - but say so.
            log.warn("Execution report for {} on unregistered session {}", clOrdId, inbound.sessionId());
        }
        return lpAccountId;
    }

    private void record(JournaledReport entry) {
        Deque<JournaledReport> forOrder =
                byClOrdId.computeIfAbsent(entry.clOrdId(), key -> new ConcurrentLinkedDeque<>());
        forOrder.addLast(entry);
        while (forOrder.size() > properties.getMaxReportsPerOrder()) {
            forOrder.pollFirst();
        }

        chronological.addLast(entry);
        while (chronological.size() > properties.getMaxReports()) {
            evict(chronological.pollFirst());
        }

        Sinks.EmitResult result = live.tryEmitNext(entry);
        if (result.isFailure() && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            log.warn("Execution report {} not delivered to subscribers: {}",
                    entry.report().getExecId(), result);
        }
    }

    @Scheduled(fixedDelayString = "${fix-gateway.execution-journal.sweep-interval-ms:60000}")
    void sweepExpired() {
        Instant cutoff = Instant.now().minusSeconds(properties.getRetentionMinutes() * 60L);
        JournaledReport oldest;
        while ((oldest = chronological.peekFirst()) != null && oldest.receivedAt().isBefore(cutoff)) {
            evict(chronological.pollFirst());
        }
    }

    /** Keeps the per-order index from outliving the chronological buffer. */
    private void evict(JournaledReport entry) {
        if (entry == null) {
            return;
        }
        Deque<JournaledReport> forOrder = byClOrdId.get(entry.clOrdId());
        if (forOrder == null) {
            return;
        }
        forOrder.remove(entry);
        if (forOrder.isEmpty()) {
            byClOrdId.remove(entry.clOrdId(), forOrder);
        }
    }
}
