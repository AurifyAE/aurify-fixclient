package com.aurify.fixclient.provider.fxcubic;

import com.aurify.fixclient.canonical.enums.CanonicalSide;
import com.aurify.fixclient.canonical.event.CanonicalOrderRequest;
import com.aurify.fixclient.provider.OutboundPolicy;
import org.junit.jupiter.api.Test;
import quickfix.Message;
import quickfix.field.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FxCubicOutgoingBuilderTest {

    private final FxCubicOutgoingBuilder builder = new FxCubicOutgoingBuilder(new FxCubicSymbolNormalizer());
    private final OutboundPolicy policy = new OutboundPolicy(Set.of("XAUUSD_1GRAM"), 0L, 0D, 0D);

    private CanonicalOrderRequest.CanonicalOrderRequestBuilder order() {
        return CanonicalOrderRequest.builder()
                .provider("fxcubic")
                .clOrdId("CL-1")
                .account("ACC-1")
                .ticketId("TICKET-1")
                .group("BRANCH-1")
                .symbol("XAUUSD_1GRAM")
                .side(CanonicalSide.BUY)
                .orderQty(new BigDecimal("10"))
                .ordType(CanonicalOrderRequest.OrdType.MARKET)
                .timeInForce(CanonicalOrderRequest.TimeInForce.IOC);
    }

    @Test
    void marketOrderCarriesTheFieldsFxCubicMandates() throws Exception {
        Message nos = builder.build(order().build(), policy, null);

        assertEquals("CL-1", nos.getString(ClOrdID.FIELD));
        assertEquals(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION,
                nos.getChar(HandlInst.FIELD));
        assertEquals(TimeInForce.IMMEDIATE_OR_CANCEL, nos.getChar(TimeInForce.FIELD));
        assertEquals(OrdType.MARKET, nos.getChar(OrdType.FIELD));
        assertEquals(Side.BUY, nos.getChar(Side.FIELD));
        assertEquals("XAUUSD_1GRAM", nos.getString(Symbol.FIELD));
        assertEquals("ACC-1", nos.getString(Account.FIELD));
    }

    @Test
    void handlInstAppearsExactlyOnce() {
        // It used to be set twice - once in the constructor and again below it.
        String raw = builder.build(order().build(), policy, null).toString();
        long occurrences = raw.chars().filter(c -> c == '\001').count();
        assertTrue(occurrences > 0, "message should be SOH-delimited");
        assertEquals(1, countField(raw, HandlInst.FIELD));
    }

    @Test
    void clOrdLinkIdUsesTheMandatoryTicketAccountGroupFormat() throws Exception {
        assertEquals("TICKET-1-ACC-1-BRANCH-1", builder.build(order().build(), policy, null).getString(583));
    }

    @Test
    void clOrdLinkIdFallsBackWhenPartsAreMissing() throws Exception {
        Message nos = builder.build(
                order().ticketId(null).account(null).group(null).build(), policy, null);
        assertEquals("0-0-Default", nos.getString(583));
    }

    @Test
    void limitOrderCarriesAPrice() throws Exception {
        Message nos = builder.build(
                order().ordType(CanonicalOrderRequest.OrdType.LIMIT)
                        .price(new BigDecimal("2345.67")).build(),
                policy, null);
        assertEquals(OrdType.LIMIT, nos.getChar(OrdType.FIELD));
        assertEquals(2345.67, nos.getDouble(Price.FIELD), 0.0001);
    }

    @Test
    void marketOrderHasNoPrice() {
        assertFalse(builder.build(order().build(), policy, null).isSetField(Price.FIELD));
    }

    @Test
    void decimalVenueQuantityCarriesIntoOrderQty() throws Exception {
        OutboundPolicy xauPolicy = new OutboundPolicy(Set.of("XAUUSD"), 0L, 0D, 0D);
        Message nos = builder.build(
                order().symbol("XAUUSD").orderQty(new BigDecimal("3.21")).build(),
                xauPolicy, null);

        assertEquals("XAUUSD", nos.getString(Symbol.FIELD));
        assertEquals(3.21, nos.getDouble(OrderQty.FIELD), 0.0001);
    }

    @Test
    void aSymbolOutsideTheAllowlistIsRefusedBeforeAnyMessageIsBuilt() {
        assertThrows(IllegalArgumentException.class,
                () -> builder.build(order().symbol("EURUSD").build(), policy, null));
    }

    /** Counts "<tag>=" occurrences at the start of a SOH-delimited field. */
    private long countField(String rawMessage, int tag) {
        String needle = "\001" + tag + "=";
        long count = 0;
        int idx = rawMessage.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = rawMessage.indexOf(needle, idx + 1);
        }
        return count;
    }

    /**
     * Regression: TransactTime (60) must be UTC.
     *
     * It was built with {@code LocalDateTime.now()}, which reads the JVM's
     * default zone, so from an IST host the tag went out 5h30m ahead of the
     * SendingTime QuickFIX had just stamped, and Finalto rejected every order
     * with "Invalid TransactTime". FXCubic does not validate the tag, so this
     * builder shipped the same defect unnoticed.
     */
    @Test
    void transactTimeIsUtcNotTheHostsLocalZone() throws Exception {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        Message nos = builder.build(order().build(), policy, null);
        LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC);

        LocalDateTime sent = nos.getUtcTimeStamp(TransactTime.FIELD);
        assertFalse(sent.isBefore(before.minusSeconds(1)), "TransactTime " + sent + " is behind UTC now");
        assertFalse(sent.isAfter(after.plusSeconds(1)), "TransactTime " + sent + " is ahead of UTC now");
    }

}
