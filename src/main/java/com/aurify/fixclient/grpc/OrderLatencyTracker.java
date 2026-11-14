package com.aurify.fixclient.grpc;

import com.aurify.fixclient.canonical.event.CanonicalExecutionReport;
import com.aurify.fixclient.canonical.event.CanonicalReject;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.field.BusinessRejectRefID;
import quickfix.field.ClOrdID;
import quickfix.field.ExecID;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stage timings for one SubmitMarketOrder, from the gRPC call arriving to its
 * response being written, so the gateway's own time can be told apart from the
 * LP's:
 *
 * <pre>
 *  received ─session─▶ session ready ─outbound─▶ sent ─lp_first_report─▶ first report on the wire
 *                                                 └──lp_terminal_report─▶ terminal report on the wire
 *                                                                          ─inbound─▶ settled ─response─▶ answered
 * </pre>
 *
 * Every finished order records one Micrometer timer per stage
 * ({@code fix.order.latency{stage,provider,session,outcome}}, with percentile
 * histograms for Prometheus) and logs one line with all stages, keyed by
 * ClOrdID so it can be matched to the caller's trace.
 *
 * "On the wire" is when QuickFIX hands the message to {@code fromApp}, before
 * the cracker, queue and pipeline - that queueing is what {@code inbound}
 * measures. {@code sent} is when {@code Session.sendToTarget} returned, so
 * {@code outbound} includes validation, the FIX build and the message store
 * write.
 */
@Slf4j
@Component
public class OrderLatencyTracker {

    static final String METRIC = "fix.order.latency";

    /** An order the service never finished (a bug or a lost thread) is dropped after this. */
    private static final long ABANDONED_AFTER_MS = 5 * 60_000L;

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Timeline> byClOrdId = new ConcurrentHashMap<>();

    public OrderLatencyTracker(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Starts timing an order. Must be called before the order is sent, so a
     * report that arrives while {@code sendToTarget} is still returning is
     * matched.
     */
    public Timeline start(String clOrdId, String provider, long receivedNanos,
                          long sessionReadyNanos, boolean coldSession) {
        Timeline timeline = new Timeline(clOrdId, provider, receivedNanos, sessionReadyNanos, coldSession);
        byClOrdId.put(clOrdId, timeline);
        return timeline;
    }

    /**
     * Notes when an order's report reached the gateway. Runs on the QuickFIX
     * thread for every application message, so it is a map lookup and a clock
     * read, and quotes return before either.
     */
    public void onFixMessageArrived(Message message) {
        if (byClOrdId.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        String clOrdId = field(message, ClOrdID.FIELD);
        if (clOrdId == null) {
            clOrdId = field(message, BusinessRejectRefID.FIELD);
        }
        if (clOrdId == null) {
            return;
        }
        Timeline timeline = byClOrdId.get(clOrdId);
        if (timeline != null) {
            timeline.reportArrived(field(message, ExecID.FIELD), now);
        }
    }

    /**
     * Runs ahead of {@link PendingOrderRegistry#onExecutionReport}, whose
     * completion of the waiting future answers the call on this same thread -
     * the settle time has to be taken before that.
     */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener
    public void onExecutionReport(CanonicalExecutionReport report) {
        if (!PendingOrderRegistry.isTerminal(report.getOrdStatus())) {
            return;
        }
        Timeline timeline = byClOrdId.get(report.getClOrdId());
        if (timeline != null) {
            timeline.settled(report.getExecId(), System.nanoTime());
        }
    }

    /** Same ordering as above, for {@link PendingOrderRegistry#onReject}. */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener
    public void onReject(CanonicalReject reject) {
        if (reject.getRefId() == null) {
            return;
        }
        Timeline timeline = byClOrdId.get(reject.getRefId());
        if (timeline != null) {
            timeline.settled(null, System.nanoTime());
        }
    }

    /** Records the stages and stops tracking. A null timeline (a duplicate submit) is ignored. */
    public void finish(Timeline timeline, String outcome) {
        if (timeline == null) {
            return;
        }
        long answered = System.nanoTime();
        byClOrdId.remove(timeline.clOrdId, timeline);

        long session = record(timeline, outcome, "session", timeline.receivedNanos, timeline.sessionReadyNanos);
        long outbound = record(timeline, outcome, "outbound", timeline.sessionReadyNanos, timeline.sentNanos);
        long lpFirst = record(timeline, outcome, "lp_first_report", timeline.sentNanos, timeline.firstReportNanos.get());
        long lpTerminal = record(timeline, outcome, "lp_terminal_report", timeline.sentNanos, timeline.terminalReportNanos);
        long inbound = record(timeline, outcome, "inbound", timeline.terminalReportNanos, timeline.settledNanos);
        long response = record(timeline, outcome, "response", timeline.settledNanos, answered);
        long total = record(timeline, outcome, "total", timeline.receivedNanos, answered);

        log.info("Order latency {}: total={} session={}({}) outbound={} lpFirstReport={} lpTerminalReport={}"
                        + " inbound={} response={} provider={} outcome={}",
                timeline.clOrdId, ms(total), ms(session), sessionTag(timeline), ms(outbound), ms(lpFirst),
                ms(lpTerminal), ms(inbound), ms(response), timeline.provider, outcome);
    }

    @Scheduled(fixedDelay = 60_000L)
    void sweepAbandoned() {
        long cutoff = System.currentTimeMillis() - ABANDONED_AFTER_MS;
        byClOrdId.values().removeIf(timeline -> timeline.startedAtEpochMs < cutoff);
    }

    int trackedCount() {
        return byClOrdId.size();
    }

    /** Records end - start, or nothing when either point was never reached. Returns -1 then. */
    private long record(Timeline timeline, String outcome, String stage, long startNanos, long endNanos) {
        if (startNanos == 0L || endNanos == 0L || endNanos < startNanos) {
            return -1L;
        }
        long nanos = endNanos - startNanos;
        Timer.builder(METRIC)
                .description("SubmitMarketOrder latency by lifecycle stage")
                .tag("stage", stage)
                .tag("provider", timeline.provider != null ? timeline.provider : "unknown")
                .tag("session", sessionTag(timeline))
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(60))
                .register(meterRegistry)
                .record(nanos, TimeUnit.NANOSECONDS);
        return nanos;
    }

    private static String sessionTag(Timeline timeline) {
        return timeline.coldSession ? "cold" : "warm";
    }

    private static String ms(long nanos) {
        return nanos < 0 ? "-" : String.format(Locale.ROOT, "%.1fms", nanos / 1_000_000.0);
    }

    private static String field(Message message, int tag) {
        try {
            return message.isSetField(tag) ? message.getString(tag) : null;
        } catch (FieldNotFound e) {
            return null;
        }
    }

    /** One order's clock readings, all from {@link System#nanoTime()}; 0 means not reached. */
    public static final class Timeline {
        private final String clOrdId;
        private final String provider;
        private final long receivedNanos;
        private final long sessionReadyNanos;
        private final boolean coldSession;
        private final long startedAtEpochMs = System.currentTimeMillis();
        /** Arrival per ExecID, so the terminal report's own arrival is used even if a later one is already in. */
        private final ConcurrentHashMap<String, Long> arrivalsByExecId = new ConcurrentHashMap<>(4);
        private final AtomicLong firstReportNanos = new AtomicLong();
        private volatile long lastReportNanos;
        private volatile long sentNanos;
        private volatile long terminalReportNanos;
        private volatile long settledNanos;

        Timeline(String clOrdId, String provider, long receivedNanos, long sessionReadyNanos, boolean coldSession) {
            this.clOrdId = clOrdId;
            this.provider = provider;
            this.receivedNanos = receivedNanos;
            this.sessionReadyNanos = sessionReadyNanos;
            this.coldSession = coldSession;
        }

        public void markSent() {
            sentNanos = System.nanoTime();
        }

        void reportArrived(String execId, long nanos) {
            firstReportNanos.compareAndSet(0L, nanos);
            lastReportNanos = nanos;
            arrivalsByExecId.put(execId != null ? execId : "", nanos);
        }

        void settled(String execId, long nanos) {
            if (settledNanos != 0L) {
                return;
            }
            Long arrived = arrivalsByExecId.get(execId != null ? execId : "");
            terminalReportNanos = arrived != null ? arrived : lastReportNanos;
            settledNanos = nanos;
        }
    }
}
