package com.aurify.fixclient.provider.finalto;

import com.aurify.fixclient.canonical.enums.CanonicalSide;
import com.aurify.fixclient.canonical.event.CanonicalOrderRequest;
import com.aurify.fixclient.canonical.event.CanonicalOutboundRequest;
import com.aurify.fixclient.provider.OutboundPolicy;
import org.springframework.stereotype.Component;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.fix44.NewOrderSingle;

import java.time.LocalDateTime;

/**
 * Builds outbound Finalto (CFH) FIX 4.4 messages from canonical requests, per
 * Finalto FIX API v2.6 (NewOrderSingle, p.18):
 *  - NoPartyIDs(453)/PartyID(448)/PartyIDSource(447)=D/PartyRole(452)=3 is
 *    REQUIRED - unlike FXCubic, which takes none of this.
 *  - HandlInst(21) is sent only for Stop/StopLimit orders; this gateway only
 *    ever builds Market/Limit, so it is never set.
 *  - ClOrdLinkID(583) does not exist in this spec and must not be sent -
 *    FXCubic's builder sends it unconditionally, which is exactly the kind of
 *    LP-specific quirk this class exists to keep out of shared code.
 *  - TimeInForce is IOC or FOK (spec p.18); it is NOT hardcoded here the way
 *    FXCubic's IOC is, because the adapter's validateOutbound enforces the
 *    allowed set instead.
 *  - Currency(15) is the dealt (base) currency; Instrument settle currency
 *    tracks it (spec p.14).
 */
@Component
public class FinaltoOutgoingBuilder {

    private final FinaltoSymbolNormalizer symbolNormalizer;

    public FinaltoOutgoingBuilder(FinaltoSymbolNormalizer symbolNormalizer) {
        this.symbolNormalizer = symbolNormalizer;
    }

    public Message build(CanonicalOutboundRequest request, OutboundPolicy policy, SessionID sessionId) {
        if (request instanceof CanonicalOrderRequest order) {
            return buildNewOrderSingle(order, policy);
        }
        throw new IllegalArgumentException("Unsupported outbound request type: " + request.getClass());
    }

    private NewOrderSingle buildNewOrderSingle(CanonicalOrderRequest order, OutboundPolicy policy) {
        NewOrderSingle nos = new NewOrderSingle(
                new ClOrdID(sanitizeClOrdId(order.getClOrdId())),
                order.getSide() == CanonicalSide.BUY ? new Side(Side.BUY) : new Side(Side.SELL),
                new TransactTime(LocalDateTime.now()),
                order.getOrdType() == CanonicalOrderRequest.OrdType.LIMIT
                        ? new OrdType(OrdType.LIMIT) : new OrdType(OrdType.MARKET)
        );
        String symbol = symbolNormalizer.normalize(order.getSymbol(), policy);
        nos.set(new Symbol(symbol));
        nos.set(new OrderQty(order.getOrderQty().doubleValue()));
        nos.set(new TimeInForce(mapTimeInForce(order.getTimeInForce())));
        if (order.getOrdType() == CanonicalOrderRequest.OrdType.LIMIT) {
            nos.set(new Price(order.getPrice().doubleValue()));
        }
        if (order.getAccount() != null) {
            nos.set(new Account(order.getAccount()));
        }
        // Dealt currency is the base of the FX pair (spec p.14) - the first
        // three characters of a six-character symbol (e.g. EURUSD -> EUR).
        if (symbol.length() >= 6) {
            nos.set(new Currency(symbol.substring(0, 3)));
        }
        if (order.getPartyId() != null && !order.getPartyId().isBlank()) {
            NewOrderSingle.NoPartyIDs party = new NewOrderSingle.NoPartyIDs();
            party.set(new PartyID(order.getPartyId()));
            party.set(new PartyIDSource(PartyIDSource.PROPRIETARY_CUSTOM_CODE));
            party.set(new PartyRole(PartyRole.CLIENT_ID));
            nos.addGroup(party);
        }
        // No HandlInst: Finalto sends it only for Stop/StopLimit orders, which
        // this gateway does not build. No ClOrdLinkID(583): FXCubic-only tag,
        // absent from the Finalto spec.
        return nos;
    }

    private char mapTimeInForce(CanonicalOrderRequest.TimeInForce tif) {
        return switch (tif) {
            case FOK -> TimeInForce.FILL_OR_KILL;
            case GTC -> TimeInForce.GOOD_TILL_CANCEL;
            case DAY -> TimeInForce.DAY;
            default -> TimeInForce.IMMEDIATE_OR_CANCEL;
        };
    }

    /** Spec p.16: ClOrdID max 50 chars, and forbidden characters <>"'%;()& . */
    private String sanitizeClOrdId(String clOrdId) {
        String cleaned = clOrdId.replaceAll("[<>\"'%;()&]", "");
        return cleaned.length() > 50 ? cleaned.substring(0, 50) : cleaned;
    }
}
