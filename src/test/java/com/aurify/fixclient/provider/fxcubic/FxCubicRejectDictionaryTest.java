package com.aurify.fixclient.provider.fxcubic;

import com.aurify.fixclient.canonical.enums.CanonicalOrdStatus;
import com.aurify.fixclient.canonical.event.CanonicalEvent;
import com.aurify.fixclient.canonical.event.CanonicalExecutionReport;
import org.junit.jupiter.api.Test;
import quickfix.DataDictionary;
import quickfix.Message;
import quickfix.MessageUtils;
import quickfix.fix43.ExecutionReport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for a rejection FXCubic really sent that the gateway could not see.
 *
 * The ExecutionReport below is copied verbatim from data/log: the order was
 * refused with "(Missing or Invalid Account)", but OrdRejReason=15 is outside
 * the FIX 4.3 enum, so strict validation threw the whole message away and the
 * caller waited out its deadline instead of being told why.
 */
class FxCubicRejectDictionaryTest {

    /** Verbatim from the wire, SOH written as \001. */
    private static final String FXCUBIC_REJECT =
            "8=FIX.4.3\0019=236\00135=8\00134=2\00149=FXC_T\00156=Olla_T\001"
            + "52=20260902-08:34:14.401\00111=6a97df831703241c6337dc3a\00139=8\001150=8\001"
            + "17=NONE\0011=INVALID\0016=0\00131=0\00155=XAUUSD_1GRAM\00154=2\00144=0\001"
            + "38=1\001151=1\00114=0\00132=0\00159=3\00140=1\00137=0\001103=15\001"
            + "58=(Missing or Invalid Account) #NONE\001526=NONE\00110=180\001";

    private DataDictionary dictionary(String resource) throws Exception {
        return new DataDictionary(resource);
    }

    @Test
    void theStockDictionaryThrowsAwayThisRejection() throws Exception {
        Message message = MessageUtils.parse(new quickfix.fix43.MessageFactory(),
                dictionary("FIX43.xml"), FXCUBIC_REJECT);

        // This is the bug: a perfectly readable rejection fails validation.
        Exception e = assertThrows(Exception.class,
                () -> dictionary("FIX43.xml").validate(message));
        assertTrue(e.getMessage().contains("103"),
                "expected the failure to be about OrdRejReason, was: " + e.getMessage());
    }

    @Test
    void theFxCubicDictionaryAcceptsIt() throws Exception {
        DataDictionary fxcubic = dictionary("FIX43-fxcubic.xml");
        Message message = MessageUtils.parse(new quickfix.fix43.MessageFactory(), fxcubic, FXCUBIC_REJECT);

        assertDoesNotThrow(() -> fxcubic.validate(message));
    }

    @Test
    void theAdapterReportsTheLpsOwnReason() throws Exception {
        DataDictionary fxcubic = dictionary("FIX43-fxcubic.xml");
        Message parsed = MessageUtils.parse(new quickfix.fix43.MessageFactory(), fxcubic, FXCUBIC_REJECT);
        assertInstanceOf(ExecutionReport.class, parsed);

        CanonicalEvent event = new FxCubicIncomingMapper().map(parsed, null);

        CanonicalExecutionReport report = assertInstanceOf(CanonicalExecutionReport.class, event);
        assertEquals(CanonicalOrdStatus.REJECTED, report.getOrdStatus());
        assertEquals("6a97df831703241c6337dc3a", report.getClOrdId(),
                "must correlate to the pending order, or the caller still times out");
        assertEquals("(Missing or Invalid Account) #NONE", report.getRejectText());
        assertEquals(15, report.getOrdRejReason(),
                "the provider's own reason code survives, it is not normalised away");
    }

    @Test
    void theRelaxedDictionaryStillValidatesEverythingElse() throws Exception {
        DataDictionary fxcubic = dictionary("FIX43-fxcubic.xml");

        Message message = MessageUtils.parse(new quickfix.fix43.MessageFactory(), fxcubic, FXCUBIC_REJECT);

        // Side=Z is not a valid FIX 4.3 side and must still be caught: only the
        // reason-code enums were relaxed, not validation as a whole.
        message.setChar(quickfix.field.Side.FIELD, 'Z');

        assertThrows(Exception.class, () -> fxcubic.validate(message),
                "relaxing reason codes must not disable validation generally");
    }
}
