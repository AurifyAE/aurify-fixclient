package com.aurify.fixclient.transport;

import com.aurify.fixclient.canonical.event.CanonicalEvent;
import com.aurify.fixclient.canonical.event.CanonicalOutboundRequest;
import com.aurify.fixclient.pipeline.InboundEvent;
import com.aurify.fixclient.pipeline.InboundMessageQueue;
import com.aurify.fixclient.provider.LiquidityProviderAdapter;
import com.aurify.fixclient.provider.OutboundPolicy;
import com.aurify.fixclient.provider.ProviderAdapterRegistry;
import com.aurify.fixclient.provider.ProviderCapabilities;
import com.aurify.fixclient.session.DirectSessionControlService;
import com.aurify.fixclient.session.LpSessionEntry;
import com.aurify.fixclient.session.LpSessionRegistry;
import com.aurify.fixclient.session.SessionRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.*;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cracker routes by MsgType (tag 35), not by the FIX-version-specific
 * class QuickFIX/J constructs the message as - that is the whole point of the
 * rewrite away from MessageCracker. These tests build both a FIX 4.3 and a
 * FIX 4.4 ExecutionReport (different classes, same MsgType) to prove neither
 * needs its own handler, and that an unhandled MsgType is dropped rather than
 * thrown or silently swallowed as a mapping failure.
 */
class GatewayMessageCrackerTest {

    private static final SessionID FIX43_SESSION =
            new SessionID("FIX.4.3", "AURIFY_TR", "LP_TR", "acct-fxcubic-TRADING");
    private static final SessionID FIX44_SESSION =
            new SessionID("FIX.4.4", "AURIFY_TR", "CFH_TR", "acct-finalto-TRADING");
    private static final SessionID UNKNOWN_SESSION =
            new SessionID("FIX.4.3", "AURIFY_TR", "NOBODY_TR", "acct-unregistered-TRADING");

    private InboundMessageQueue queue;
    private GatewayMessageCracker cracker;

    @BeforeEach
    void setUp() {
        LpSessionRegistry sessionRegistry = new LpSessionRegistry();
        sessionRegistry.put(new LpSessionEntry("acct-fxcubic", SessionRole.TRADING, FIX43_SESSION, "fxcubic", "fp"));
        sessionRegistry.put(new LpSessionEntry("acct-finalto", SessionRole.TRADING, FIX44_SESSION, "finalto", "fp"));

        ProviderAdapterRegistry adapterRegistry =
                new ProviderAdapterRegistry(List.of(new StubAdapter("fxcubic"), new StubAdapter("finalto")), sessionRegistry);

        queue = new InboundMessageQueue();
        cracker = new GatewayMessageCracker(adapterRegistry, queue);
    }

    private List<InboundEvent> collect() {
        List<InboundEvent> received = new CopyOnWriteArrayList<>();
        queue.stream().subscribe(received::add);
        return received;
    }

    @Test
    void aFix43ExecutionReportIsRoutedToItsAdapter() throws FieldNotFound {
        List<InboundEvent> received = collect();
        cracker.crack(fix43ExecutionReport(), FIX43_SESSION);

        assertEquals(1, received.size());
        assertEquals("fxcubic", received.get(0).event().provider());
    }

    @Test
    void aFix44ExecutionReportIsRoutedTheSameWayWithNoNewHandler() throws FieldNotFound {
        List<InboundEvent> received = collect();
        cracker.crack(fix44ExecutionReport(), FIX44_SESSION);

        assertEquals(1, received.size());
        assertEquals("finalto", received.get(0).event().provider());
    }

    @Test
    void marketDataIsDroppedRatherThanRoutedOrThrown() {
        List<InboundEvent> received = collect();
        quickfix.fix44.MarketDataSnapshotFullRefresh snapshot = new quickfix.fix44.MarketDataSnapshotFullRefresh();
        assertDoesNotThrow(() -> cracker.crack(snapshot, FIX44_SESSION));
        assertTrue(received.isEmpty());
    }

    @Test
    void anUnknownMsgTypeIsDroppedRatherThanThrown() {
        List<InboundEvent> received = collect();
        quickfix.fix44.News news = new quickfix.fix44.News(new Headline("test"));
        assertDoesNotThrow(() -> cracker.crack(news, FIX43_SESSION));
        assertTrue(received.isEmpty());
    }

    @Test
    void aReportOnAnUnregisteredSessionIsLoggedNotThrown() throws FieldNotFound {
        List<InboundEvent> received = collect();
        assertDoesNotThrow(() -> cracker.crack(fix43ExecutionReport(), UNKNOWN_SESSION));
        assertTrue(received.isEmpty());
    }

    private quickfix.fix43.ExecutionReport fix43ExecutionReport() {
        quickfix.fix43.ExecutionReport report = new quickfix.fix43.ExecutionReport(
                new OrderID("ORD-1"), new ExecID("EXEC-1"), new ExecType(ExecType.FILL), new OrdStatus(OrdStatus.FILLED),
                new Side(Side.BUY), new LeavesQty(0), new CumQty(10), new AvgPx(2345.0));
        report.set(new ClOrdID("CL-1"));
        report.set(new Symbol("XAUUSD_1GRAM"));
        report.set(new OrderQty(10));
        return report;
    }

    private quickfix.fix44.ExecutionReport fix44ExecutionReport() {
        quickfix.fix44.ExecutionReport report = new quickfix.fix44.ExecutionReport(
                new OrderID("ORD-2"), new ExecID("EXEC-2"), new ExecType(ExecType.FILL), new OrdStatus(OrdStatus.FILLED),
                new Side(Side.BUY), new LeavesQty(0), new CumQty(1000), new AvgPx(1.085));
        report.set(new ClOrdID("CL-2"));
        report.set(new Symbol("EURUSD"));
        report.set(new OrderQty(1000));
        return report;
    }

    /** Stub adapter that maps any message to a bare canonical event carrying
     *  only the provider name - enough to prove routing, without pulling in
     *  the real fxcubic/finalto mappers. */
    private record StubAdapter(String name) implements LiquidityProviderAdapter {
        @Override
        public String providerName() {
            return name;
        }

        @Override
        public ProviderCapabilities capabilities() {
            return ProviderCapabilities.builder().fixVersion("FIX.4.3").build();
        }

        @Override
        public CanonicalEvent mapIncoming(Message fixMessage, SessionID sessionId) {
            String provider = name;
            return new CanonicalEvent() {
                @Override
                public String provider() {
                    return provider;
                }

                @Override
                public Instant occurredAt() {
                    return Instant.now();
                }
            };
        }

        @Override
        public Message buildOutgoing(CanonicalOutboundRequest request, OutboundPolicy policy, SessionID sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onPostLogonStartup(SessionID sessionId, DirectSessionControlService sessionControl) {
        }

        @Override
        public com.aurify.fixclient.provider.ValidationResult validateOutbound(
                CanonicalOutboundRequest request, OutboundPolicy policy) {
            return com.aurify.fixclient.provider.ValidationResult.ok();
        }

        @Override
        public String normalizeSymbol(String rawSymbol, OutboundPolicy policy) {
            return rawSymbol;
        }
    }
}
