package com.aurify.fixclient.provider.stonex;

import com.aurify.fixclient.canonical.enums.CanonicalExecType;
import com.aurify.fixclient.canonical.enums.CanonicalOrdStatus;
import com.aurify.fixclient.canonical.enums.CanonicalSide;
import com.aurify.fixclient.canonical.event.CanonicalExecutionReport;
import com.aurify.fixclient.canonical.event.CanonicalQuote;
import com.aurify.fixclient.canonical.event.CanonicalReject;
import org.junit.jupiter.api.Test;
import quickfix.DataDictionary;
import quickfix.Message;
import quickfix.MessageUtils;
import quickfix.field.*;
import quickfix.fix43.ExecutionReport;
import quickfix.fix43.MarketDataRequestReject;
import quickfix.fix43.MarketDataSnapshotFullRefresh;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Builds the messages FX Inside documents (FIX Client API 5.1, 3.3.2 and
 * 4.3.10), serialises them, then parses and validates them through the
 * dictionary the session really uses - so a tag the dictionary would refuse
 * fails here instead of as a silent timeout against the venue.
 */
class StoneXIncomingMapperTest {

    private final StoneXIncomingMapper mapper = new StoneXIncomingMapper();

    /** What the session runs with: FIX43-stonex.xml, user-defined tags not checked. */
    private static DataDictionary stonexDictionary() throws Exception {
        DataDictionary dd = new DataDictionary("FIX43-stonex.xml");
        dd.setCheckUserDefinedFields(false);
        return dd;
    }

    private static Message roundTrip(Message outbound, DataDictionary dd) throws Exception {
        outbound.getHeader().setString(BeginString.FIELD, "FIX.4.3");
        outbound.getHeader().setString(SenderCompID.FIELD, "demo.fxgrid");
        outbound.getHeader().setString(TargetCompID.FIELD, "order.APITESTINTM.082");
        outbound.getHeader().setString(TargetSubID.FIELD, "APITESTINTM");
        outbound.getHeader().setString(OnBehalfOfCompID.FIELD, "BRKD");
        outbound.getHeader().setInt(MsgSeqNum.FIELD, 7);
        outbound.getHeader().setUtcTimeStamp(SendingTime.FIELD, LocalDateTime.now(ZoneOffset.UTC));
        Message parsed = MessageUtils.parse(new quickfix.fix43.MessageFactory(), dd, outbound.toString());
        dd.validate(parsed);
        return parsed;
    }

    private static ExecutionReport report(char execType, char ordStatus, String cumQty, String leavesQty) {
        ExecutionReport er = new ExecutionReport(
                new OrderID("FXI-123"), new ExecID("EX-1"), new ExecType(execType), new OrdStatus(ordStatus),
                new Side(Side.BUY), new LeavesQty(Double.parseDouble(leavesQty)),
                new CumQty(Double.parseDouble(cumQty)), new AvgPx(2650.15));
        er.set(new ClOrdID("CL-1"));
        er.set(new Symbol("XAU/USD"));
        er.set(new Product(4));
        er.set(new SecurityType("FOR"));
        er.set(new OrderQty(3.21));
        er.set(new Currency("XAU"));
        er.set(new ExecInst("B"));
        er.set(new TimeInForce(TimeInForce.IMMEDIATE_OR_CANCEL));
        er.set(new TransactTime(LocalDateTime.now(ZoneOffset.UTC)));
        return er;
    }

    @Test
    void aTradeReportIsAFillAndCompletesTheHedge() throws Exception {
        ExecutionReport er = report(ExecType.TRADE, OrdStatus.FILLED, "3.21", "0");
        er.set(new LastQty(3.21));
        er.set(new LastPx(2650.15));
        er.setString(MidPx.FIELD, "2650.10");   // documented on 35=8, not allowed there by stock FIX43.xml
        er.setString(7631, "0");                 // MidPxFl, user-defined range

        CanonicalExecutionReport mapped =
                (CanonicalExecutionReport) mapper.map(roundTrip(er, stonexDictionary()), null);

        assertEquals(CanonicalExecType.FILL, mapped.getExecType(), "FX Inside fills with ExecType=F, may skip New");
        assertEquals(CanonicalOrdStatus.FILLED, mapped.getOrdStatus());
        assertEquals("CL-1", mapped.getClOrdId());
        assertEquals("XAU/USD", mapped.getSymbol());
        assertEquals(CanonicalSide.BUY, mapped.getSide());
        assertEquals(new BigDecimal("3.21"), mapped.getLastQty(), "fractional ounces survive exactly");
        assertEquals(new BigDecimal("3.21"), mapped.getCumQty());
        assertEquals("stonex", mapped.getProvider());
    }

    @Test
    void theStockDictionaryWouldThrowThatSameFillAway() throws Exception {
        ExecutionReport er = report(ExecType.TRADE, OrdStatus.FILLED, "3.21", "0");
        er.setString(MidPx.FIELD, "2650.10");
        DataDictionary stock = new DataDictionary("FIX43.xml");
        stock.setCheckUserDefinedFields(false);

        assertThrows(Exception.class, () -> roundTrip(er, stock),
                "if this starts passing, FIX43-stonex.xml may no longer be needed");
    }

    @Test
    void stoppedMeansTheVenueRefusedTheOrderAndIsTerminal() throws Exception {
        ExecutionReport er = report(ExecType.STOPPED, OrdStatus.STOPPED, "0", "0");
        er.set(new Text("Insufficient credit"));

        CanonicalExecutionReport mapped =
                (CanonicalExecutionReport) mapper.map(roundTrip(er, stonexDictionary()), null);

        assertEquals(CanonicalOrdStatus.REJECTED, mapped.getOrdStatus(),
                "left UNKNOWN, the caller would wait out its deadline");
        assertEquals(CanonicalExecType.REJECTED, mapped.getExecType());
        assertEquals("Insufficient credit", mapped.getRejectText());
    }

    @Test
    void anIocRemainderCancelIsTerminal() throws Exception {
        ExecutionReport er = report(ExecType.CANCELED, OrdStatus.CANCELED, "1.00", "0");

        CanonicalExecutionReport mapped =
                (CanonicalExecutionReport) mapper.map(roundTrip(er, stonexDictionary()), null);

        assertEquals(CanonicalOrdStatus.CANCELLED, mapped.getOrdStatus());
        assertEquals(new BigDecimal("1.00"), mapped.getCumQty().setScale(2));
    }

    @Test
    void aRejectionWithAVenueReasonCodeOutsideTheFixEnumStillArrives() throws Exception {
        ExecutionReport er = report(ExecType.REJECTED, OrdStatus.REJECTED, "0", "0");
        er.set(new OrdRejReason(99));
        er.set(new ExecInst("ST"));   // not a FIX enum value; FX Inside sends it for algo orders

        CanonicalExecutionReport mapped =
                (CanonicalExecutionReport) mapper.map(roundTrip(er, stonexDictionary()), null);

        assertEquals(CanonicalOrdStatus.REJECTED, mapped.getOrdStatus());
        assertEquals(99, mapped.getOrdRejReason());
    }

    @Test
    void pendingCancelStaysNonTerminal() {
        assertEquals(CanonicalOrdStatus.UNKNOWN, StoneXIncomingMapper.mapOrdStatus(OrdStatus.PENDING_CANCEL));
    }

    private static MarketDataSnapshotFullRefresh.NoMDEntries entry(char type, String px, String size, String cond) {
        MarketDataSnapshotFullRefresh.NoMDEntries e = new MarketDataSnapshotFullRefresh.NoMDEntries();
        e.set(new MDEntryType(type));
        e.set(new MDEntryPx(Double.parseDouble(px)));
        e.set(new Currency("XAU"));
        e.set(new MDEntrySize(Double.parseDouble(size)));
        e.setString(QuoteCondition.FIELD, cond);
        e.set(new MDEntryOriginator("BRKD"));
        e.set(new QuoteEntryID("Q-" + type + px));
        e.set(new MDEntryPositionNo(0));
        return e;
    }

    @Test
    void aSnapshotYieldsTheBestTradablePricePerSide() throws Exception {
        MarketDataSnapshotFullRefresh w = new MarketDataSnapshotFullRefresh();
        w.set(new MDReqID("md-1"));
        w.set(new Symbol("XAU/USD"));
        w.set(new Product(4));
        w.set(new MaturityDate("20261117"));
        w.addGroup(entry(MDEntryType.BID, "2650.10", "100", "A"));
        w.addGroup(entry(MDEntryType.BID, "2650.40", "50", "B"));  // inactive stream: not tradable
        w.addGroup(entry(MDEntryType.BID, "2650.30", "0", "A"));   // zero size: ignore
        w.addGroup(entry(MDEntryType.OFFER, "2650.60", "100", "A"));
        w.addGroup(entry(MDEntryType.OFFER, "0", "100", "A"));     // zero price: ignore

        CanonicalQuote quote = (CanonicalQuote) mapper.map(roundTrip(w, stonexDictionary()), null);

        assertEquals("XAU/USD", quote.getSymbol());
        assertEquals(new BigDecimal("2650.1"), quote.getBid().stripTrailingZeros());
        assertEquals(new BigDecimal("2650.6"), quote.getAsk().stripTrailingZeros());
        assertEquals("md-1", quote.getQuoteId());
    }

    @Test
    void aMarketDataRejectCarriesTheVenueReason() throws Exception {
        MarketDataRequestReject y = new MarketDataRequestReject(new MDReqID("md-1"));
        y.set(new MDReqRejReason(MDReqRejReason.INSUFFICIENT_PERMISSIONS));
        y.set(new Text("Not permissioned for XAU/USD"));

        CanonicalReject reject = (CanonicalReject) mapper.map(roundTrip(y, stonexDictionary()), null);

        assertEquals("md-1", reject.getRefId());
        assertEquals("3", reject.getReasonCode());
        assertEquals("Not permissioned for XAU/USD", reject.getText());
    }
}
