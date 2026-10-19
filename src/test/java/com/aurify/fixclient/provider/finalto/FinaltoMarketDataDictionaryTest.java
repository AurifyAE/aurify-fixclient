package com.aurify.fixclient.provider.finalto;

import com.aurify.fixclient.canonical.event.CanonicalEvent;
import com.aurify.fixclient.canonical.event.CanonicalQuote;
import org.junit.jupiter.api.Test;
import quickfix.DataDictionary;
import quickfix.Message;
import quickfix.MessageUtils;
import quickfix.field.*;
import quickfix.fix44.MarketDataIncrementalRefresh;
import quickfix.fix44.MarketDataSnapshotFullRefresh;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for Finalto quotes the gateway rejected instead of delivering.
 *
 * Finalto stamps SettlDate(64) on its market data. The stock FIX44.xml does
 * not list it for MarketDataSnapshotFullRefresh, so every quote failed
 * validation and the gateway answered each one with
 * "35=3 ... 58=Tag not defined for this message type, field=64".
 */
class FinaltoMarketDataDictionaryTest {

    private static final int SETTL_DATE = 64;

    private DataDictionary dictionary(String resource) throws Exception {
        return new DataDictionary(resource);
    }

    private void header(Message message) {
        message.getHeader().setString(SenderCompID.FIELD, "CfhDemoPrices");
        message.getHeader().setString(TargetCompID.FIELD, "Aurify");
        message.getHeader().setInt(MsgSeqNum.FIELD, 50);
        message.getHeader().setUtcTimeStamp(SendingTime.FIELD, LocalDateTime.of(2026, 10, 19, 17, 59, 57));
    }

    private MarketDataSnapshotFullRefresh.NoMDEntries entry(char type, double px, boolean withSettlDate) {
        MarketDataSnapshotFullRefresh.NoMDEntries entry = new MarketDataSnapshotFullRefresh.NoMDEntries();
        entry.set(new MDEntryType(type));
        entry.set(new MDEntryPx(px));
        entry.set(new MDEntrySize(100));
        if (withSettlDate) entry.setString(SETTL_DATE, "20261021");
        return entry;
    }

    /** SettlDate either on the message itself or inside each price entry - both must pass. */
    private String snapshot(boolean onBody, boolean onEntries) {
        MarketDataSnapshotFullRefresh snapshot = new MarketDataSnapshotFullRefresh();
        header(snapshot);
        snapshot.set(new MDReqID("req-1"));
        snapshot.set(new Symbol("XAUUSD"));
        if (onBody) snapshot.setString(SETTL_DATE, "20261021");
        snapshot.addGroup(entry(MDEntryType.BID, 2650.40, onEntries));
        snapshot.addGroup(entry(MDEntryType.OFFER, 2650.90, onEntries));
        return snapshot.toString();
    }

    private Message parse(DataDictionary dictionary, String raw) throws Exception {
        return MessageUtils.parse(new quickfix.fix44.MessageFactory(), dictionary, raw);
    }

    @Test
    void theStockDictionaryRejectsTheQuote() throws Exception {
        DataDictionary stock = dictionary("FIX44.xml");
        Message message = parse(stock, snapshot(true, false));

        Exception e = assertThrows(Exception.class, () -> stock.validate(message));
        assertTrue(e.getMessage().contains(String.valueOf(SETTL_DATE)),
                "expected the failure to be about SettlDate(64), was: " + e.getMessage());
    }

    @Test
    void theFinaltoDictionaryAcceptsSettlDateOnTheMessage() throws Exception {
        DataDictionary finalto = dictionary("FIX44-finalto.xml");
        Message message = parse(finalto, snapshot(true, false));

        assertDoesNotThrow(() -> finalto.validate(message));
    }

    @Test
    void theFinaltoDictionaryAcceptsSettlDateInsideEachEntry() throws Exception {
        DataDictionary finalto = dictionary("FIX44-finalto.xml");
        Message message = parse(finalto, snapshot(false, true));

        assertDoesNotThrow(() -> finalto.validate(message));
        assertEquals(2, message.getInt(NoMDEntries.FIELD),
                "SettlDate must not end the repeating group early and lose the offer");
    }

    @Test
    void theFinaltoDictionaryAcceptsSettlDateOnIncrementalUpdates() throws Exception {
        MarketDataIncrementalRefresh incremental = new MarketDataIncrementalRefresh();
        header(incremental);
        incremental.set(new MDReqID("req-1"));
        incremental.setString(SETTL_DATE, "20261021");
        MarketDataIncrementalRefresh.NoMDEntries entry = new MarketDataIncrementalRefresh.NoMDEntries();
        entry.set(new MDUpdateAction(MDUpdateAction.CHANGE));
        entry.set(new MDEntryType(MDEntryType.BID));
        entry.set(new Symbol("XAUUSD"));
        entry.set(new MDEntryPx(2650.45));
        entry.setString(SETTL_DATE, "20261021");
        incremental.addGroup(entry);

        DataDictionary finalto = dictionary("FIX44-finalto.xml");
        Message message = parse(finalto, incremental.toString());

        assertDoesNotThrow(() -> finalto.validate(message));
    }

    @Test
    void theAdapterTurnsTheQuoteIntoABidAndAsk() throws Exception {
        DataDictionary finalto = dictionary("FIX44-finalto.xml");
        Message parsed = parse(finalto, snapshot(true, true));
        assertInstanceOf(MarketDataSnapshotFullRefresh.class, parsed);

        CanonicalEvent event = new FinaltoIncomingMapper().map(parsed, null);

        CanonicalQuote quote = assertInstanceOf(CanonicalQuote.class, event);
        assertEquals("XAUUSD", quote.getSymbol());
        assertEquals(0, new BigDecimal("2650.40").compareTo(quote.getBid()));
        assertEquals(0, new BigDecimal("2650.90").compareTo(quote.getAsk()));
    }

    @Test
    void theFinaltoDictionaryStillValidatesEverythingElse() throws Exception {
        DataDictionary finalto = dictionary("FIX44-finalto.xml");
        Message message = parse(finalto, snapshot(true, false));

        // TradSesStatus is not a MarketDataSnapshotFullRefresh field and must
        // still be caught: only SettlDate was added, validation is not relaxed.
        message.setInt(TradSesStatus.FIELD, 2);

        assertThrows(Exception.class, () -> finalto.validate(message),
                "allowing SettlDate must not disable validation generally");
    }

    @Test
    void theAdapterUsesTheFinaltoDictionaryForFix44Only() {
        FinaltoProviderAdapter adapter = new FinaltoProviderAdapter(null, null, null, null, null);

        assertEquals("FIX44-finalto.xml", adapter.dataDictionary("FIX.4.4"));
        assertEquals("FIX43.xml", adapter.dataDictionary("FIX.4.3"));
    }
}
