package com.aurify.fixclient.journal;

import com.aurify.fixclient.canonical.enums.CanonicalExecType;
import com.aurify.fixclient.canonical.enums.CanonicalOrdStatus;
import com.aurify.fixclient.canonical.event.CanonicalExecutionReport;
import com.aurify.fixclient.canonical.event.CanonicalReject;
import com.aurify.fixclient.config.ExecutionJournalProperties;
import com.aurify.fixclient.pipeline.InboundEvent;
import com.aurify.fixclient.session.LpSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quickfix.SessionID;
import reactor.core.Disposable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The journal is what stops an order's record from freezing at whatever the
 * hedge call happened to return, so these cover the cases where a report
 * arrives outside that call: after it, out of order, or as a reject.
 */
class ExecutionJournalTest {

    private static final SessionID SESSION =
            new SessionID("FIX.4.3", "AURIFY_TR", "LP_TR", "acct-1-TRADING");

    private ExecutionJournal journal;

    @BeforeEach
    void setUp() {
        journal = new ExecutionJournal(new ExecutionJournalProperties(), new LpSessionRegistry());
    }

    private CanonicalExecutionReport report(String clOrdId, String execId,
                                            CanonicalOrdStatus status, String cumQty) {
        return CanonicalExecutionReport.builder()
                .provider("fxcubic")
                .clOrdId(clOrdId)
                .execId(execId)
                .execType(status == CanonicalOrdStatus.FILLED ? CanonicalExecType.FILL : CanonicalExecType.NEW)
                .ordStatus(status)
                .cumQty(new BigDecimal(cumQty))
                .occurredAt(Instant.now())
                .build();
    }

    private void deliver(CanonicalExecutionReport report, String rawFix) {
        journal.onInboundEvent(new InboundEvent(SESSION, rawFix, report));
    }

    @Test
    void keepsEveryReportForAnOrderInArrivalOrder() {
        deliver(report("ord-1", "e1", CanonicalOrdStatus.NEW, "0"), "8=FIX.4.3|39=0");
        deliver(report("ord-1", "e2", CanonicalOrdStatus.PARTIALLY_FILLED, "3"), "8=FIX.4.3|39=1");
        deliver(report("ord-1", "e3", CanonicalOrdStatus.FILLED, "5"), "8=FIX.4.3|39=2");

        List<JournaledReport> reports = journal.reportsFor("ord-1");

        assertEquals(List.of("e1", "e2", "e3"),
                reports.stream().map(entry -> entry.report().getExecId()).toList());
        assertEquals("8=FIX.4.3|39=2", reports.get(2).rawFix());
    }

    @Test
    void keepsOrdersApart() {
        deliver(report("ord-1", "e1", CanonicalOrdStatus.FILLED, "5"), null);
        deliver(report("ord-2", "e2", CanonicalOrdStatus.FILLED, "7"), null);

        assertEquals(1, journal.reportsFor("ord-1").size());
        assertEquals("e2", journal.reportsFor("ord-2").get(0).report().getExecId());
        assertTrue(journal.reportsFor("unknown-order").isEmpty());
    }

    /** A reject arrives as its own message type, so without this it would never
     *  reach the caller's ledger and the order would look merely unanswered. */
    @Test
    void recordsAnOrderRejectAsATerminalReport() {
        CanonicalReject reject = CanonicalReject.builder()
                .provider("fxcubic")
                .refId("ord-9")
                .reasonCode("11")
                .text("Unknown symbol")
                .occurredAt(Instant.now())
                .build();

        journal.onInboundEvent(new InboundEvent(SESSION, "8=FIX.4.3|35=3", reject));

        List<JournaledReport> reports = journal.reportsFor("ord-9");
        assertEquals(1, reports.size());
        assertEquals(CanonicalOrdStatus.REJECTED, reports.get(0).report().getOrdStatus());
        assertEquals("Unknown symbol", reports.get(0).report().getRejectText());
    }

    /** A session-level reject names a sequence number, not an order - filing it
     *  against one would attach an unrelated failure to a real hedge. */
    @Test
    void ignoresASessionLevelReject() {
        CanonicalReject reject = CanonicalReject.builder()
                .provider("fxcubic")
                .refId(null)
                .text("Invalid MsgSeqNum")
                .occurredAt(Instant.now())
                .build();

        journal.onInboundEvent(new InboundEvent(SESSION, "8=FIX.4.3|35=3", reject));

        assertTrue(journal.reportsFor("ord-1").isEmpty());
    }

    /** What a reconnecting subscriber depends on: the gap first, then live. */
    @Test
    void replaysFromTheGivenInstantThenGoesLive() throws InterruptedException {
        deliver(report("ord-1", "old", CanonicalOrdStatus.NEW, "0"), null);
        Thread.sleep(5); // the journal orders by arrival, so the cutoff needs a gap to sit in
        Instant cutoff = Instant.now();
        deliver(report("ord-1", "recent", CanonicalOrdStatus.FILLED, "5"), null);

        List<String> received = new CopyOnWriteArrayList<>();
        Disposable subscription = journal.stream(cutoff)
                .subscribe(entry -> received.add(entry.report().getExecId()));

        deliver(report("ord-2", "live", CanonicalOrdStatus.FILLED, "2"), null);
        subscription.dispose();

        assertEquals(List.of("recent", "live"), received);
    }

    @Test
    void aSubscriberWithoutASinceReceivesOnlyLiveReports() throws InterruptedException {
        deliver(report("ord-1", "before-subscribe", CanonicalOrdStatus.FILLED, "5"), null);
        Thread.sleep(5); // "live only" is resolved at subscribe time, so leave it a gap

        List<String> received = new CopyOnWriteArrayList<>();
        Disposable subscription = journal.stream(null)
                .subscribe(entry -> received.add(entry.report().getExecId()));

        deliver(report("ord-2", "after-subscribe", CanonicalOrdStatus.FILLED, "2"), null);
        subscription.dispose();

        assertEquals(List.of("after-subscribe"), received);
    }

    /** A disposed subscription must stop receiving, or the gateway accumulates a
     *  subscriber for every client that has ever connected. */
    @Test
    void stopsDeliveringAfterASubscriberGoesAway() {
        List<String> received = new CopyOnWriteArrayList<>();
        Disposable subscription = journal.stream(null)
                .subscribe(entry -> received.add(entry.report().getExecId()));
        subscription.dispose();

        deliver(report("ord-1", "after-dispose", CanonicalOrdStatus.FILLED, "5"), null);

        assertTrue(received.isEmpty());
    }

    /** One runaway order must not be able to evict every other order's history. */
    @Test
    void capsTheHistoryHeldForASingleOrder() {
        ExecutionJournalProperties properties = new ExecutionJournalProperties();
        properties.setMaxReportsPerOrder(3);
        journal = new ExecutionJournal(properties, new LpSessionRegistry());

        for (int i = 1; i <= 5; i++) {
            deliver(report("ord-1", "e" + i, CanonicalOrdStatus.PARTIALLY_FILLED, String.valueOf(i)), null);
        }

        assertEquals(List.of("e3", "e4", "e5"),
                journal.reportsFor("ord-1").stream().map(entry -> entry.report().getExecId()).toList());
    }
}
