package com.aurify.fixclient.provider.stonex;

import com.aurify.fixclient.canonical.enums.CanonicalSide;
import com.aurify.fixclient.canonical.event.CanonicalMarketDataRequest;
import com.aurify.fixclient.canonical.event.CanonicalOrderRequest;
import com.aurify.fixclient.provider.OutboundPolicy;
import org.junit.jupiter.api.Test;
import quickfix.Group;
import quickfix.Message;
import quickfix.field.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StoneXOutgoingBuilderTest {

    private final StoneXOutgoingBuilder builder = new StoneXOutgoingBuilder(new StoneXSymbolNormalizer());
    private final OutboundPolicy policy = new OutboundPolicy(Set.of("XAUUSD"), 0L, 0D, 0D);

    private CanonicalOrderRequest.CanonicalOrderRequestBuilder order() {
        return CanonicalOrderRequest.builder()
                .provider("stonex")
                .clOrdId("CL-1")
                .symbol("XAUUSD")
                .side(CanonicalSide.BUY)
                .orderQty(new BigDecimal("3.21"))
                .ordType(CanonicalOrderRequest.OrdType.MARKET)
                .timeInForce(CanonicalOrderRequest.TimeInForce.IOC)
                .deliverToCompId("BRKD");
    }

    @Test
    void marketOrderCarriesEveryFieldFxInsideRequires() throws Exception {
        Message nos = builder.build(order().build(), policy, null);

        assertEquals("CL-1", nos.getString(ClOrdID.FIELD));
        assertEquals(OrdType.MARKET, nos.getChar(OrdType.FIELD));
        assertEquals(Side.BUY, nos.getChar(Side.FIELD));
        assertEquals("XAU/USD", nos.getString(Symbol.FIELD), "FX Inside spells pairs BASE/TERM");
        assertEquals("XAU", nos.getString(Currency.FIELD), "dealt currency is the base, so qty is in ounces");
        assertEquals(new BigDecimal("3.21"), new BigDecimal(nos.getString(OrderQty.FIELD)));
        assertEquals("B", nos.getString(ExecInst.FIELD), "without B a market order never crosses");
        assertEquals(4, nos.getInt(Product.FIELD));
        assertEquals("FOR", nos.getString(SecurityType.FIELD));
        assertEquals(TimeInForce.IMMEDIATE_OR_CANCEL, nos.getChar(TimeInForce.FIELD));
        assertEquals(0d, nos.getDouble(MinQty.FIELD), "IOC: any fill size");
        assertFalse(nos.isSetField(Price.FIELD), "a market order has no price");
        assertFalse(nos.isSetField(QuoteID.FIELD), "QuoteID is only for previously-quoted orders");
        assertEquals("BRKD", nos.getHeader().getString(DeliverToCompID.FIELD));
    }

    @Test
    void fokSetsMinQtyToTheWholeOrder() throws Exception {
        Message nos = builder.build(order().timeInForce(CanonicalOrderRequest.TimeInForce.FOK).build(), policy, null);

        assertEquals(TimeInForce.FILL_OR_KILL, nos.getChar(TimeInForce.FIELD));
        assertEquals(3.21d, nos.getDouble(MinQty.FIELD));
    }

    @Test
    void limitOrderCarriesItsPrice() throws Exception {
        Message nos = builder.build(order()
                .ordType(CanonicalOrderRequest.OrdType.LIMIT)
                .price(new BigDecimal("2650.15"))
                .side(CanonicalSide.SELL)
                .build(), policy, null);

        assertEquals(OrdType.LIMIT, nos.getChar(OrdType.FIELD));
        assertEquals(Side.SELL, nos.getChar(Side.FIELD));
        assertEquals(2650.15d, nos.getDouble(Price.FIELD));
    }

    @Test
    void deliverToAndAccountAreOmittedWhenNotConfigured() {
        Message nos = builder.build(order().deliverToCompId(null).build(), policy, null);

        assertFalse(nos.getHeader().isSetField(DeliverToCompID.FIELD));
        assertFalse(nos.isSetField(Account.FIELD));
    }

    @Test
    void transactTimeIsUtc() throws Exception {
        Message nos = builder.build(order().build(), policy, null);

        LocalDateTime sent = nos.getUtcTimeStamp(TransactTime.FIELD);
        long skew = Math.abs(Duration.between(sent, LocalDateTime.now(ZoneOffset.UTC)).toSeconds());
        assertTrue(skew < 60, "TransactTime must be UTC, was " + sent);
    }

    @Test
    void aSymbolOutsideTheAllowlistIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> builder.build(order().symbol("EURUSD").build(), policy, null));
    }

    @Test
    void marketDataRequestIsAddressedToTheProviderWithProductInTheSymbolGroup() throws Exception {
        CanonicalMarketDataRequest request = CanonicalMarketDataRequest.builder()
                .provider("stonex")
                .mdReqId("md-1")
                .symbols(List.of("XAUUSD"))
                .subscriptionRequestType(CanonicalMarketDataRequest.SubscriptionRequestType.SNAPSHOT_PLUS_UPDATES)
                .marketDepth(1)
                .deliverToCompId("BRKD")
                .build();

        Message mdr = builder.build(request, policy, null);

        assertEquals("BRKD", mdr.getHeader().getString(DeliverToCompID.FIELD), "required on 35=V (p.72)");
        assertEquals("md-1", mdr.getString(MDReqID.FIELD));
        assertEquals(SubscriptionRequestType.SNAPSHOT_UPDATES, mdr.getChar(SubscriptionRequestType.FIELD));
        assertEquals(1, mdr.getInt(MarketDepth.FIELD));
        assertEquals(MDUpdateType.FULL_REFRESH, mdr.getInt(MDUpdateType.FIELD));

        assertEquals(2, mdr.getGroupCount(NoMDEntryTypes.FIELD));
        assertEquals(1, mdr.getGroupCount(NoRelatedSym.FIELD));
        Group related = mdr.getGroups(NoRelatedSym.FIELD).get(0);
        assertEquals("XAU/USD", related.getString(Symbol.FIELD));
        assertEquals(4, related.getInt(Product.FIELD), "a request without Product is rejected (p.73)");
    }
}
