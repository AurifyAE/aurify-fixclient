package com.aurify.fixclient.provider.finalto;

import com.aurify.fixclient.canonical.enums.CanonicalSide;
import com.aurify.fixclient.canonical.event.CanonicalMarketDataRequest;
import com.aurify.fixclient.canonical.event.CanonicalOrderRequest;
import com.aurify.fixclient.canonical.event.CanonicalOutboundRequest;
import com.aurify.fixclient.provider.OutboundPolicy;
import org.springframework.stereotype.Component;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.fix44.MarketDataRequest;
import quickfix.fix44.NewOrderSingle;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

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
        if (request instanceof CanonicalMarketDataRequest mdRequest) {
            return buildMarketDataRequest(mdRequest);
        }
        throw new IllegalArgumentException("Unsupported outbound request type: " + request.getClass());
    }

    /**
     * Request shape confirmed against the live CfhDemoPrices session rather
     * than a spec document (none is checked in): Bid+Offer, MarketDepth=1, one
     * symbol per request.
     *
     * MDUpdateType(265) is mandatory here and is what distinguishes this from
     * FXCubic's request. Omitting it does not produce a MarketDataRequestReject
     * (35=Y) naming the subscription - the venue answers with a session-level
     * BusinessMessageReject "Conditionally Required Field Missing (265)"
     * (35=j, 372=V, 380=5) and then simply never sends a price, so the symptom
     * is a pricing session that logs on cleanly and stays silent.
     *
     * FULL_REFRESH asks for 35=W snapshots. At depth 1 a snapshot already
     * carries the whole book, so there is nothing for 35=X to add, and it keeps
     * the venue from sending deletes we would have to fold into cached state.
     * The incremental path stays mapped for venues that send it anyway.
     */
    private MarketDataRequest buildMarketDataRequest(CanonicalMarketDataRequest request) {
        boolean unsubscribe = request.getSubscriptionRequestType()
                == CanonicalMarketDataRequest.SubscriptionRequestType.UNSUBSCRIBE;
        char subscriptionType = unsubscribe
                ? SubscriptionRequestType.DISABLE_PREVIOUS_SNAPSHOT_UPDATE_REQUEST
                : SubscriptionRequestType.SNAPSHOT_UPDATES;

        MarketDataRequest mdr = new MarketDataRequest(
                new MDReqID(request.getMdReqId()),
                new SubscriptionRequestType(subscriptionType),
                new MarketDepth(request.getMarketDepth() > 0 ? request.getMarketDepth() : 1));

        // Conditionally required: only when this establishes a subscription.
        if (!unsubscribe) {
            mdr.set(new MDUpdateType(MDUpdateType.FULL_REFRESH));
        }

        MarketDataRequest.NoMDEntryTypes bidType = new MarketDataRequest.NoMDEntryTypes();
        bidType.set(new MDEntryType(MDEntryType.BID));
        mdr.addGroup(bidType);
        MarketDataRequest.NoMDEntryTypes offerType = new MarketDataRequest.NoMDEntryTypes();
        offerType.set(new MDEntryType(MDEntryType.OFFER));
        mdr.addGroup(offerType);

        for (String symbol : request.getSymbols()) {
            MarketDataRequest.NoRelatedSym related = new MarketDataRequest.NoRelatedSym();
            related.set(new Symbol(symbol));
            mdr.addGroup(related);
        }
        return mdr;
    }

    private NewOrderSingle buildNewOrderSingle(CanonicalOrderRequest order, OutboundPolicy policy) {
        NewOrderSingle nos = new NewOrderSingle(
                new ClOrdID(sanitizeClOrdId(order.getClOrdId())),
                order.getSide() == CanonicalSide.BUY ? new Side(Side.BUY) : new Side(Side.SELL),
                new TransactTime(utcNow()),
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

    /**
     * TransactTime (60) must be UTC, like every other FIX timestamp.
     *
     * {@code LocalDateTime.now()} reads the JVM's default zone, so on a host
     * that is not on UTC the tag goes out shifted - from IST it arrives 5h30m
     * in the future while QuickFIX's own SendingTime (52) is correct, and
     * Finalto rejects the order with "Invalid TransactTime". FXCubic does not
     * validate it, which is why this survived until a second venue was added.
     */
    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

}
