package com.aurify.fixclient.loadtest;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Running totals for one fake LP: what it received, how it answered, how long that took. */
public final class FakeLpStats {

    private final long startedAt = System.currentTimeMillis();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong finished = new AtomicLong();
    private final AtomicLong totalFinalMs = new AtomicLong();
    private final Map<FakeLpOutcomes.Kind, AtomicLong> byKind = new EnumMap<>(FakeLpOutcomes.Kind.class);

    FakeLpStats() {
        for (FakeLpOutcomes.Kind kind : FakeLpOutcomes.Kind.values()) {
            byKind.put(kind, new AtomicLong());
        }
    }

    void received() {
        received.incrementAndGet();
    }

    void finished(FakeLpOutcomes.Kind kind, long finalAfterMs) {
        finished.incrementAndGet();
        totalFinalMs.addAndGet(finalAfterMs);
        byKind.get(kind).incrementAndGet();
    }

    public long receivedCount() {
        return received.get();
    }

    public long finishedCount() {
        return finished.get();
    }

    public long count(FakeLpOutcomes.Kind kind) {
        return byKind.get(kind).get();
    }

    String summary() {
        double seconds = Math.max(1, System.currentTimeMillis() - startedAt) / 1000.0;
        long done = finished.get();
        return String.format("received %d, answered %d (%.1f/s): filled %d, partial %d, no fill %d, rejected %d; "
                        + "mean time to final report %s",
                received.get(), done, done / seconds,
                count(FakeLpOutcomes.Kind.FILL), count(FakeLpOutcomes.Kind.PARTIAL),
                count(FakeLpOutcomes.Kind.NO_FILL), count(FakeLpOutcomes.Kind.REJECT),
                done == 0 ? "-" : (totalFinalMs.get() / done) + "ms");
    }
}
