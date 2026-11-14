package com.aurify.fixclient.grpc;

import com.aurify.fixclient.canonical.enums.CanonicalOrdStatus;
import com.aurify.fixclient.canonical.event.CanonicalExecutionReport;
import com.aurify.fixclient.canonical.event.CanonicalReject;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import quickfix.Message;
import quickfix.field.BusinessRejectRefID;
import quickfix.field.ClOrdID;
import quickfix.field.ExecID;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stage split is only useful if each stage is measured between the right
 * two points: a report's own arrival, not whichever one came last, and a
 * settle time taken before the waiting call is answered.
 */
class OrderLatencyTrackerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OrderLatencyTracker tracker = new OrderLatencyTracker(registry);

    @Test
    void aFilledOrderRecordsEveryStage() {
        OrderLatencyTracker.Timeline timeline = startAndSend("c1");
        tracker.onFixMessageArrived(executionReport("c1", "ack-1"));
        tracker.onExecutionReport(report("c1", "ack-1", CanonicalOrdStatus.PENDING_NEW));
        tracker.onFixMessageArrived(executionReport("c1", "fill-1"));
        tracker.onExecutionReport(report("c1", "fill-1", CanonicalOrdStatus.FILLED));

        tracker.finish(timeline, "filled");

        for (String stage : new String[] {"session", "outbound", "lp_first_report", "lp_terminal_report",
                "inbound", "response", "total"}) {
            assertEquals(1, timer(registry, stage, "filled").count(), stage);
        }
        assertEquals(0, tracker.trackedCount());
    }

    @Test
    void theTerminalReportIsTimedFromItsOwnArrival() throws InterruptedException {
        OrderLatencyTracker.Timeline timeline = startAndSend("c2");
        tracker.onFixMessageArrived(executionReport("c2", "fill-2"));
        Thread.sleep(30);
        // A later report is already in when the fill is processed.
        tracker.onFixMessageArrived(executionReport("c2", "late-2"));
        tracker.onExecutionReport(report("c2", "fill-2", CanonicalOrdStatus.FILLED));
        tracker.finish(timeline, "filled");

        // inbound runs from the fill's arrival, so it includes the 30ms wait.
        assertTrue(timer(registry, "inbound", "filled").totalTime(TimeUnit.MILLISECONDS) >= 30);
    }

    @Test
    void aBusinessRejectIsMatchedByItsRefId() {
        OrderLatencyTracker.Timeline timeline = startAndSend("c3");
        Message reject = new Message();
        reject.setField(new BusinessRejectRefID("c3"));
        tracker.onFixMessageArrived(reject);
        tracker.onReject(CanonicalReject.builder().refId("c3").text("no").build());
        tracker.finish(timeline, "lp_reject");

        assertEquals(1, timer(registry, "lp_terminal_report", "lp_reject").count());
        assertEquals(1, timer(registry, "inbound", "lp_reject").count());
    }

    @Test
    void aTimeoutRecordsOnlyTheStagesItReached() {
        OrderLatencyTracker.Timeline timeline = startAndSend("c4");
        tracker.finish(timeline, "timeout");

        assertEquals(1, timer(registry, "outbound", "timeout").count());
        assertEquals(1, timer(registry, "total", "timeout").count());
        assertNull(registry.find(OrderLatencyTracker.METRIC).tag("stage", "lp_first_report").timer());
        assertNull(registry.find(OrderLatencyTracker.METRIC).tag("stage", "inbound").timer());
    }

    @Test
    void untrackedOrdersAndDuplicatesAreIgnored() {
        tracker.onFixMessageArrived(executionReport("someone-else", "x"));
        tracker.onExecutionReport(report("someone-else", "x", CanonicalOrdStatus.FILLED));
        tracker.finish(null, "filled");

        assertTrue(registry.getMeters().isEmpty());
    }

    /**
     * The registry answers the call on the publishing thread as soon as it
     * completes the future, so the tracker's listener has to have run first or
     * the inbound and response stages are lost.
     */
    @Test
    void theSettleTimeIsTakenBeforeTheRegistryAnswersTheCall() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.registerBean(PendingOrderRegistry.class);
            context.registerBean(OrderLatencyTracker.class);
            context.refresh();

            OrderLatencyTracker springTracker = context.getBean(OrderLatencyTracker.class);
            MeterRegistry meters = context.getBean(MeterRegistry.class);
            PendingOrderRegistry.Registration registration =
                    context.getBean(PendingOrderRegistry.class).register("key-5", "c5");
            OrderLatencyTracker.Timeline timeline =
                    springTracker.start("c5", "fxcubic", System.nanoTime(), System.nanoTime(), false);
            timeline.markSent();
            registration.getFuture().whenComplete((report, error) -> springTracker.finish(timeline, "filled"));

            springTracker.onFixMessageArrived(executionReport("c5", "fill-5"));
            context.publishEvent(report("c5", "fill-5", CanonicalOrdStatus.FILLED));

            assertTrue(registration.getFuture().isDone());
            assertEquals(1, timer(meters, "inbound", "filled").count());
            assertEquals(1, timer(meters, "response", "filled").count());
        }
    }

    private OrderLatencyTracker.Timeline startAndSend(String clOrdId) {
        long received = System.nanoTime();
        OrderLatencyTracker.Timeline timeline = tracker.start(clOrdId, "fxcubic", received, System.nanoTime(), false);
        timeline.markSent();
        return timeline;
    }

    private static Message executionReport(String clOrdId, String execId) {
        Message message = new Message();
        message.setField(new ClOrdID(clOrdId));
        message.setField(new ExecID(execId));
        return message;
    }

    private static CanonicalExecutionReport report(String clOrdId, String execId, CanonicalOrdStatus status) {
        return CanonicalExecutionReport.builder().clOrdId(clOrdId).execId(execId).ordStatus(status).build();
    }

    private static Timer timer(MeterRegistry meters, String stage, String outcome) {
        Timer timer = meters.find(OrderLatencyTracker.METRIC).tag("stage", stage).tag("outcome", outcome).timer();
        assertNotNull(timer, "no timer for stage " + stage);
        return timer;
    }
}
