package com.aurify.fixclient.provider.finalto;

import com.aurify.fixclient.canonical.enums.CanonicalExecType;
import com.aurify.fixclient.canonical.enums.CanonicalOrdStatus;
import com.aurify.fixclient.canonical.event.CanonicalEvent;
import com.aurify.fixclient.canonical.event.CanonicalExecutionReport;
import com.aurify.fixclient.canonical.event.CanonicalReject;
import org.junit.jupiter.api.Test;
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.OrderCancelReject;
import quickfix.fix44.Reject;

import static org.junit.jupiter.api.Assertions.*;

class FinaltoIncomingMapperTest {

    private static final SessionID SESSION =
            new SessionID("FIX.4.4", "AURIFY_TR", "CFH_TR", "acct-1-TRADING");

    private final FinaltoIncomingMapper mapper = new FinaltoIncomingMapper();

    private ExecutionReport report(char execType, char ordStatus, String cumQty, String leavesQty) {
        ExecutionReport report = new ExecutionReport(
                new OrderID("ORD-1"), new ExecID("EXEC-1"), new ExecType(execType), new OrdStatus(ordStatus),
                new Side(Side.BUY), new LeavesQty(Double.parseDouble(leavesQty)),
                new CumQty(Double.parseDouble(cumQty)), new AvgPx(1.0850));
        report.set(new ClOrdID("CL-1"));
        report.set(new Symbol("EURUSD"));
        report.set(new OrderQty(1000));
        // Finalto custom tags, both in the user-defined range (>= 5000).
        report.setString(5001, "0.0002"); // MarkUp
        report.setString(5003, "true");   // Track
        return report;
    }

    @Test
    void pendingNewThenFilledBothMapCleanly() throws Exception {
        CanonicalEvent pendingEvent = mapper.map(
                report(ExecType.PENDING_NEW, OrdStatus.PENDING_NEW, "0", "1000"), SESSION);
        assertInstanceOf(CanonicalExecutionReport.class, pendingEvent);
        CanonicalExecutionReport pending = (CanonicalExecutionReport) pendingEvent;
        assertEquals(CanonicalExecType.PENDING_NEW, pending.getExecType());
        assertEquals(CanonicalOrdStatus.PENDING_NEW, pending.getOrdStatus());

        CanonicalEvent filledEvent = mapper.map(
                report(ExecType.FILL, OrdStatus.FILLED, "1000", "0"), SESSION);
        CanonicalExecutionReport filled = (CanonicalExecutionReport) filledEvent;
        assertEquals(CanonicalExecType.FILL, filled.getExecType());
        assertEquals(CanonicalOrdStatus.FILLED, filled.getOrdStatus());
        assertEquals("CL-1", filled.getClOrdId());
        assertEquals("EURUSD", filled.getSymbol());
    }

    @Test
    void customTagsDoNotPreventTheMessageFromBeingParsed() {
        // The point of ValidateUserDefinedFields=N: the mapper never even looks
        // at 5001/5003, but the message must still parse and map without them
        // tripping dictionary validation upstream.
        assertDoesNotThrow(() -> mapper.map(report(ExecType.FILL, OrdStatus.FILLED, "1000", "0"), SESSION));
    }

    @Test
    void orderCancelRejectCarriesTheRejectTextAndClOrdId() throws Exception {
        OrderCancelReject reject = new OrderCancelReject(
                new OrderID("ORD-1"), new ClOrdID("CL-1"), new OrigClOrdID("CL-0"),
                new OrdStatus(OrdStatus.REJECTED), new CxlRejResponseTo(CxlRejResponseTo.ORDER_CANCEL_REQUEST));
        reject.set(new CxlRejReason(CxlRejReason.OTHER));
        reject.set(new Text("Unknown symbol"));

        CanonicalEvent event = mapper.map(reject, SESSION);
        assertInstanceOf(CanonicalReject.class, event);
        CanonicalReject canonical = (CanonicalReject) event;
        assertEquals("CL-1", canonical.getRefId());
        assertEquals("Unknown symbol", canonical.getText());
    }

    @Test
    void sessionLevelRejectCarriesRefSeqNumNotAnOrderId() throws Exception {
        Reject reject = new Reject(new RefSeqNum(4));
        reject.set(new SessionRejectReason(SessionRejectReason.OTHER));
        reject.set(new Text("Invalid MsgSeqNum"));

        CanonicalEvent event = mapper.map(reject, SESSION);
        CanonicalReject canonical = (CanonicalReject) event;
        assertEquals("SeqNum:4", canonical.getRefId());
    }
}
