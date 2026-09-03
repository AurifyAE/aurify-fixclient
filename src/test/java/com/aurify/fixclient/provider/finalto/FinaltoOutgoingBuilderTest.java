package com.aurify.fixclient.provider.finalto;

import com.aurify.fixclient.canonical.enums.CanonicalSide;
import com.aurify.fixclient.canonical.event.CanonicalOrderRequest;
import com.aurify.fixclient.provider.OutboundPolicy;
import org.junit.jupiter.api.Test;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.field.*;
import quickfix.fix44.NewOrderSingle;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FinaltoOutgoingBuilderTest {

    private final FinaltoOutgoingBuilder builder = new FinaltoOutgoingBuilder(new FinaltoSymbolNormalizer());
    private final OutboundPolicy policy = new OutboundPolicy(Set.of("EURUSD"), 0L, 0D, 0D);

    private CanonicalOrderRequest.CanonicalOrderRequestBuilder order() {
        return CanonicalOrderRequest.builder()
                .provider("finalto")
                .clOrdId("CL-1")
                .account("ACC-1")
                .symbol("EURUSD")
                .side(CanonicalSide.BUY)
                .orderQty(new BigDecimal("1000"))
                .ordType(CanonicalOrderRequest.OrdType.MARKET)
                .timeInForce(CanonicalOrderRequest.TimeInForce.IOC)
                .partyId("PARTY-1");
    }

    @Test
    void marketOrderCarriesTheFieldsFinaltoMandates() throws Exception {
        Message nos = builder.build(order().build(), policy, null);

        assertEquals("CL-1", nos.getString(ClOrdID.FIELD));
        assertEquals(OrdType.MARKET, nos.getChar(OrdType.FIELD));
        assertEquals(Side.BUY, nos.getChar(Side.FIELD));
        assertEquals("EURUSD", nos.getString(Symbol.FIELD));
        assertEquals("ACC-1", nos.getString(Account.FIELD));
        assertEquals(TimeInForce.IMMEDIATE_OR_CANCEL, nos.getChar(TimeInForce.FIELD));
        assertEquals("EUR", nos.getString(Currency.FIELD));
    }

    @Test
    void noPartyIdsGroupIsPresentWithTheSpecMandatedFields() throws Exception {
        NewOrderSingle nos = (NewOrderSingle) builder.build(order().build(), policy, null);

        assertTrue(nos.isSetField(NoPartyIDs.FIELD));
        NewOrderSingle.NoPartyIDs group = new NewOrderSingle.NoPartyIDs();
        nos.getGroup(1, group);
        assertEquals("PARTY-1", group.getPartyID().getValue());
        assertEquals(PartyIDSource.PROPRIETARY_CUSTOM_CODE, group.getPartyIDSource().getValue());
        assertEquals(PartyRole.CLIENT_ID, group.getPartyRole().getValue());
    }

    @Test
    void noPartyIdsGroupIsOmittedWhenPartyIdIsBlank() {
        Message nos = builder.build(order().partyId(null).build(), policy, null);
        assertFalse(nos.isSetField(NoPartyIDs.FIELD));
    }

    @Test
    void handlInstIsNeverSentForMarketOrLimitOrders() {
        Message nos = builder.build(order().build(), policy, null);
        assertFalse(nos.isSetField(HandlInst.FIELD));
    }

    @Test
    void clOrdLinkIdDoesNotExistInThisSpec() {
        Message nos = builder.build(order().build(), policy, null);
        assertFalse(nos.isSetField(583));
    }

    @Test
    void fokIsMappedThrough() throws FieldNotFound {
        Message nos = builder.build(order().timeInForce(CanonicalOrderRequest.TimeInForce.FOK).build(), policy, null);
        assertEquals(TimeInForce.FILL_OR_KILL, nos.getChar(TimeInForce.FIELD));
    }

    @Test
    void limitOrderCarriesAPrice() throws Exception {
        Message nos = builder.build(
                order().ordType(CanonicalOrderRequest.OrdType.LIMIT)
                        .price(new BigDecimal("1.0850")).build(),
                policy, null);
        assertEquals(OrdType.LIMIT, nos.getChar(OrdType.FIELD));
        assertEquals(1.0850, nos.getDouble(Price.FIELD), 0.0001);
    }

    @Test
    void clOrdIdIsSanitizedAndCappedAtFiftyCharacters() throws Exception {
        String dirty = "A".repeat(60) + "<>\"'%;()&";
        Message nos = builder.build(order().clOrdId(dirty).build(), policy, null);
        String clOrdId = nos.getString(ClOrdID.FIELD);
        assertEquals(50, clOrdId.length());
        assertFalse(clOrdId.matches(".*[<>\"'%;()&].*"));
    }

    @Test
    void aSymbolOutsideTheAllowlistIsRefusedBeforeAnyMessageIsBuilt() {
        assertThrows(IllegalArgumentException.class,
                () -> builder.build(order().symbol("GBPUSD").build(), policy, null));
    }
}
