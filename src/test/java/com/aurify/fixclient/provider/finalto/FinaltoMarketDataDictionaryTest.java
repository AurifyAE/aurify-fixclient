package com.aurify.fixclient.provider.finalto;

import com.aurify.fixclient.canonical.event.CanonicalEvent;
import com.aurify.fixclient.canonical.event.CanonicalQuote;
import org.junit.jupiter.api.Test;
import quickfix.DataDictionary;
import quickfix.Message;
import quickfix.MessageUtils;
import quickfix.field.NoMDEntries;
import quickfix.field.TradSesStatus;
import quickfix.fix44.MarketDataIncrementalRefresh;
import quickfix.fix44.MarketDataSnapshotFullRefresh;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for Finalto quotes the gateway rejected instead of delivering.
 *
 * Per Finalto FIX API v2.6 (4.2.4/4.2.5) each price entry carries
 * SettlDate(64) - first entry only - and, on snapshots, MDEntryID(278).
 * The stock FIX44.xml allows neither on MarketDataSnapshotFullRefresh, so
 * every quote failed validation and the gateway answered each one with
 * "35=3 ... 58=Tag not defined for this message type, field=64".
 *
 * The messages below follow the spec's field order exactly.
 */
class FinaltoMarketDataDictionaryTest {

    /** 4.2.4: 262, 55, 268, then 269 270 271 290 64 278 per entry (64 only on the first). */
    private static final String SNAPSHOT = wire("W",
            "262=req-1|55=XAUUSD|268=2|"
            + "269=0|270=2650.40|271=100|290=1|64=20261021|278=39/1-105099|"
            + "269=1|270=2650.90|271=100|290=1|278=39/1-105100|");

    /** 4.2.5: 262, 268, then 279 269 278 270 271 290 64 55 per entry. */
    private static final String INCREMENTAL = wire("X",
            "262=req-1|268=1|"
            + "279=1|269=0|278=39/1-105101|270=2650.45|271=100|290=1|64=20261021|55=XAUUSD|");

    /** Wraps a body ('|' for SOH) in a header and trailer with real BodyLength and CheckSum. */
    private static String wire(String msgType, String body) {
        String afterLength = ("35=" + msgType + "|49=CfhDemoPrices|56=Aurify|34=50|52=20261019-17:59:57.863|" + body)
                .replace('|', '\001');
        String head = "8=FIX.4.4\0019=" + afterLength.length() + "\001" + afterLength;
        int sum = 0;
        for (byte b : head.getBytes(StandardCharsets.US_ASCII)) sum += b;
        return head + String.format("10=%03d\001", sum % 256);
    }

    private DataDictionary dictionary(String resource) throws Exception {
        return new DataDictionary(resource);
    }

    private Message parse(DataDictionary dictionary, String raw) throws Exception {
        return MessageUtils.parse(new quickfix.fix44.MessageFactory(), dictionary, raw);
    }

    @Test
    void theStockDictionaryRejectsTheQuote() throws Exception {
        DataDictionary stock = dictionary("FIX44.xml");
        Message message = parse(stock, SNAPSHOT);

        Exception e = assertThrows(Exception.class, () -> stock.validate(message));
        assertTrue(e.getMessage().contains("64"),
                "expected the failure to be about SettlDate(64), was: " + e.getMessage());
    }

    @Test
    void theFinaltoDictionaryAcceptsTheSnapshot() throws Exception {
        DataDictionary finalto = dictionary("FIX44-finalto.xml");
        Message message = parse(finalto, SNAPSHOT);

        assertDoesNotThrow(() -> finalto.validate(message));
        assertEquals(2, message.getInt(NoMDEntries.FIELD),
                "SettlDate and MDEntryID must not end the repeating group early and lose the offer");
    }

    @Test
    void theFinaltoDictionaryAcceptsTheIncrementalUpdate() throws Exception {
        DataDictionary finalto = dictionary("FIX44-finalto.xml");
        Message message = parse(finalto, INCREMENTAL);

        assertDoesNotThrow(() -> finalto.validate(message));
    }

    @Test
    void theAdapterTurnsTheSnapshotIntoABidAndAsk() throws Exception {
        Message parsed = parse(dictionary("FIX44-finalto.xml"), SNAPSHOT);
        assertInstanceOf(MarketDataSnapshotFullRefresh.class, parsed);

        CanonicalEvent event = new FinaltoIncomingMapper().map(parsed, null);

        CanonicalQuote quote = assertInstanceOf(CanonicalQuote.class, event);
        assertEquals("XAUUSD", quote.getSymbol());
        assertEquals(0, new BigDecimal("2650.40").compareTo(quote.getBid()));
        assertEquals(0, new BigDecimal("2650.90").compareTo(quote.getAsk()));
    }

    @Test
    void theAdapterTurnsTheIncrementalUpdateIntoABid() throws Exception {
        Message parsed = parse(dictionary("FIX44-finalto.xml"), INCREMENTAL);
        assertInstanceOf(MarketDataIncrementalRefresh.class, parsed);

        CanonicalEvent event = new FinaltoIncomingMapper().map(parsed, null);

        CanonicalQuote quote = assertInstanceOf(CanonicalQuote.class, event);
        assertEquals("XAUUSD", quote.getSymbol());
        assertEquals(0, new BigDecimal("2650.45").compareTo(quote.getBid()));
    }

    @Test
    void theFinaltoDictionaryStillValidatesEverythingElse() throws Exception {
        DataDictionary finalto = dictionary("FIX44-finalto.xml");
        Message message = parse(finalto, SNAPSHOT);

        // TradSesStatus is not a MarketDataSnapshotFullRefresh field and must
        // still be caught: only the spec's entry fields were added.
        message.setInt(TradSesStatus.FIELD, 2);

        assertThrows(Exception.class, () -> finalto.validate(message),
                "allowing Finalto's entry fields must not disable validation generally");
    }

    @Test
    void theAdapterUsesTheFinaltoDictionaryForFix44Only() {
        FinaltoProviderAdapter adapter = new FinaltoProviderAdapter(null, null, null, null, null);

        assertEquals("FIX44-finalto.xml", adapter.dataDictionary("FIX.4.4"));
        assertEquals("FIX43.xml", adapter.dataDictionary("FIX.4.3"));
    }
}
