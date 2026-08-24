package com.aurify.fixclient.grpc;

import com.aurify.fixclient.canonical.event.CanonicalExecutionReport;
import lombok.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Correlates a synchronous gRPC call with the asynchronous FIX execution report. */
@Component
public class PendingOrderRegistry {
    private final ConcurrentHashMap<String, PendingOrder> byIdempotencyKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingOrder> byClOrdId = new ConcurrentHashMap<>();

    public Registration register(String idempotencyKey, String clOrdId) {
        PendingOrder candidate = new PendingOrder(clOrdId);
        PendingOrder existing = byIdempotencyKey.putIfAbsent(idempotencyKey, candidate);
        if (existing != null) return new Registration(existing.future, false);
        byClOrdId.put(clOrdId, candidate);
        return new Registration(candidate.future, true);
    }

    public void discard(String idempotencyKey, String clOrdId) {
        PendingOrder pending = byIdempotencyKey.remove(idempotencyKey);
        if (pending != null) byClOrdId.remove(clOrdId, pending);
    }

    @EventListener
    public void onExecutionReport(CanonicalExecutionReport report) {
        PendingOrder pending = byClOrdId.get(report.getClOrdId());
        if (pending != null) pending.future.complete(report);
    }

    @Value
    public static class Registration {
        CompletableFuture<CanonicalExecutionReport> future;
        boolean owner;
    }

    private static class PendingOrder {
        private final CompletableFuture<CanonicalExecutionReport> future = new CompletableFuture<>();
        private PendingOrder(String ignoredClOrdId) { }
    }
}
