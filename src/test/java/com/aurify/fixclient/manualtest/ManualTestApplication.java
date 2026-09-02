package com.aurify.fixclient.manualtest;

import quickfix.*;
import quickfix.field.*;
import quickfix.fix43.NewOrderSingle;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static quickfix.field.HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION;

/** Minimal Application implementation for ManualFirstOrderTest only.
 *  Deliberately not production code - no async pipeline, no adapters, just
 *  enough to prove Logon -> Order -> ExecutionReport works end to end. */
class ManualTestApplication implements Application {

    private final String testSymbol;
    private final String username;
    private final String password;
    private final CountDownLatch logonLatch = new CountDownLatch(1);
    private final CountDownLatch execReportLatch = new CountDownLatch(1);
    private SessionID sessionId;

    // Credentials are passed in from the environment by ManualFirstOrderTest
    ManualTestApplication(String testSymbol, String username, String password) {
        this.testSymbol = testSymbol;
        this.username = username;
        this.password = password;
    }

    void awaitLogon(Duration timeout) throws InterruptedException {
        if (!logonLatch.await(timeout.toSeconds(), TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for Logon - check host/port/credentials.");
        }
    }

    boolean awaitExecutionReport(Duration timeout) throws InterruptedException {
        return execReportLatch.await(timeout.toSeconds(), TimeUnit.SECONDS);
    }

    void sendTestOrder() throws SessionNotFound {
        NewOrderSingle order = new NewOrderSingle(
                new ClOrdID("MANUALTEST-" + System.currentTimeMillis()),
                new HandlInst(AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION),
                new Side(Side.BUY),
                new TransactTime(LocalDateTime.now()),
                new OrdType(OrdType.MARKET)
        );
        order.set(new Symbol(testSymbol));
        order.set(new OrderQty(1000)); // smallest size your FXCubic demo account allows
        order.set(new TimeInForce(TimeInForce.IMMEDIATE_OR_CANCEL));
        order.set(new HandlInst(AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
        // FXCubic mandatory ClOrdLinkID (583): ticket-account-group, all three populated
        order.setString(583, "1-1-ManualTest");

        System.out.println("Sending order: " + order);
        Session.sendToTarget(order, sessionId);
    }

    @Override
    public void onCreate(SessionID sessionId) {
        this.sessionId = sessionId;
        System.out.println("Session created: " + sessionId);
    }

    @Override
    public void onLogon(SessionID sessionId) {
        System.out.println("Logon: " + sessionId);
        logonLatch.countDown();
    }

    @Override
    public void onLogout(SessionID sessionId) {
        System.out.println("Logout: " + sessionId);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        try {
            if (MsgType.LOGON.equals(message.getHeader().getString(MsgType.FIELD))) {
                message.setField(new Username(username));
                message.setField(new Password(password));
            }
        } catch (FieldNotFound e) {
            System.err.println("Missing MsgType on outgoing admin message: " + e.getMessage());
        }
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId) {
        System.out.println("fromAdmin: " + message);
    }

    @Override
    public void toApp(Message message, SessionID sessionId) {
        System.out.println("toApp: " + message);
    }

    @Override
    public void fromApp(Message message, SessionID sessionId) throws FieldNotFound {
        System.out.println("fromApp: " + message);
        if (message instanceof quickfix.fix43.ExecutionReport report) {
            System.out.println("Received ExecutionReport: OrdStatus=" + report.getOrdStatus().getValue()
                    + " ExecType=" + report.getExecType().getValue()
                    + " ClOrdID=" + report.getClOrdID().getValue());
            execReportLatch.countDown();
        }
    }
}
