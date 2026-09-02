package com.aurify.fixclient.grpc;

import io.grpc.Context;
import io.grpc.Deadline;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LpHedgeGatewayServiceTimeoutTest {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private LpHedgeGatewayService serviceWithOrderTimeout(long orderTimeoutMs) {
        GrpcGatewayProperties properties = new GrpcGatewayProperties();
        properties.setOrderTimeoutMs(orderTimeoutMs);
        return new LpHedgeGatewayService(null, null, properties, null, null);
    }

    @Test
    void withoutADeadlineTheConfiguredTimeoutApplies() {
        assertEquals(10_000L, serviceWithOrderTimeout(10_000L).timeoutMs());
    }

    @Test
    void answersBeforeTheCallerDeadlineSoTheReasonIsNotLostToDeadlineExceeded() throws Exception {
        // The caller (Node) sends a 10s deadline and the gateway is configured
        // for 10s too. Waiting the full 10s means the caller gives up first and
        // sees DEADLINE_EXCEEDED rather than FIX_EXECUTION_REPORT_TIMEOUT.
        LpHedgeGatewayService service = serviceWithOrderTimeout(10_000L);
        Deadline callerDeadline = Deadline.after(10, TimeUnit.SECONDS);

        long timeout = Context.current().withDeadline(callerDeadline, scheduler)
                .call(service::timeoutMs);

        assertTrue(timeout < 10_000L, "must finish inside the caller's deadline, got " + timeout);
        assertTrue(timeout > 8_000L, "but should still use most of it, got " + timeout);
    }

    @Test
    void theConfiguredTimeoutStillCapsAGenerousDeadline() throws Exception {
        LpHedgeGatewayService service = serviceWithOrderTimeout(3_000L);

        long timeout = Context.current()
                .withDeadline(Deadline.after(60, TimeUnit.SECONDS), scheduler)
                .call(service::timeoutMs);

        assertEquals(3_000L, timeout);
    }

    @Test
    void anAlreadyExpiredDeadlineNeverProducesANegativeWait() throws Exception {
        LpHedgeGatewayService service = serviceWithOrderTimeout(10_000L);

        long timeout = Context.current()
                .withDeadline(Deadline.after(1, TimeUnit.MILLISECONDS), scheduler)
                .call(service::timeoutMs);

        assertTrue(timeout >= 1, "timeout must stay positive, got " + timeout);
    }
}
