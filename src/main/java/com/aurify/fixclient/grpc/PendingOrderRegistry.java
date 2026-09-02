package com.aurify.fixclient.grpc;

import com.aurify.fixclient.canonical.enums.CanonicalOrdStatus;
import com.aurify.fixclient.canonical.event.CanonicalExecutionReport;
import com.aurify.fixclient.canonical.event.CanonicalReject;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Correlates a synchronous gRPC call with the asynchronous FIX execution report.
 *
 *  Process-local by design: the gateway must run as a single instance until this
 *  is backed by shared durable storage. */
@Slf4j
@Component
public class PendingOrderRegistry {
    private final ConcurrentHashMap<String, PendingOrder> byIdempotencyKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingOrder> byClOrdId = new ConcurrentHashMap<>();

    public Registration register(String idempotencyKey, String clOrdId) {
        PendingOrder candidate = new PendingOrder();
        PendingOrder existing = byIdempotencyKey.putIfAbsent(idempotencyKey, candidate);
        if (existing != null) return new Registration(existing, false);
        byClOrdId.put(clOrdId, candidate);
        return new Registration(candidate, true);
    }

    public void discard(String idempotencyKey, String clOrdId) {
        PendingOrder pending = byIdempotencyKey.remove(idempotencyKey);
        if (pending != null) byClOrdId.remove(clOrdId, pending);
    }

    /**
     * Completes the waiting call only on a *terminal* report.
     *
     * An LP typically acknowledges first and decides after: FXCubic sends
     * PENDING_NEW ("Command queued") and, milliseconds later, the real outcome.
     * Answering on the first report would report an order as accepted that the
     * venue went on to reject - the caller would book a hedge that does not
     * exist. Intermediate acks are kept so a timeout can still report what is
     * known, clearly marked non-terminal.
     */
    @EventListener
    public void onExecutionReport(CanonicalExecutionReport report) {
        PendingOrder pending = byClOrdId.get(report.getClOrdId());
        if (pending == null) {
            return;
        }
        if (isTerminal(report.getOrdStatus())) {
            pending.future.complete(report);
        } else {
            log.debug("Order {} acknowledged as {} - waiting for a terminal report",
                    report.getClOrdId(), report.getOrdStatus());
            pending.lastAck = report;
        }
    }

    /**
     * A reject that names an order must fail the waiting call immediately.
     * Without this the caller sat until its full timeout for a reply the LP had
     * already refused to send.
     */
    @EventListener
    public void onReject(CanonicalReject reject) {
        String refId = reject.getRefId();
        if (refId == null) {
            return; // session-level reject: refers to a sequence number, not an order
        }
        PendingOrder pending = byClOrdId.get(refId);
        if (pending == null) {
            return;
        }
        log.warn("Order {} rejected by {}: reason={} text={}",
                refId, reject.getProvider(), reject.getReasonCode(), reject.getText());
        pending.future.completeExceptionally(new OrderRejectedException(reject));
    }

    static boolean isTerminal(CanonicalOrdStatus status) {
        return status == CanonicalOrdStatus.FILLED
                || status == CanonicalOrdStatus.REJECTED
                || status == CanonicalOrdStatus.CANCELLED;
    }

    /** Carries the LP's own reason so it can be relayed to the caller verbatim. */
    public static class OrderRejectedException extends RuntimeException {
        private final transient CanonicalReject reject;

        OrderRejectedException(CanonicalReject reject) {
            super(reject.getText() != null ? reject.getText() : "Rejected by liquidity provider");
            this.reject = reject;
        }

        public CanonicalReject getReject() {
            return reject;
        }
    }

    @Value
    public static class Registration {
        PendingOrder pendingOrder;
        boolean owner;

        public CompletableFuture<CanonicalExecutionReport> getFuture() {
            return pendingOrder.future;
        }

        /** The last non-terminal acknowledgement, if the LP sent one before we gave up. */
        public CanonicalExecutionReport getLastAck() {
            return pendingOrder.lastAck;
        }
    }

    public static class PendingOrder {
        private final CompletableFuture<CanonicalExecutionReport> future = new CompletableFuture<>();
        private volatile CanonicalExecutionReport lastAck;
    }
}
