package com.aurify.fixclient.provider.fxcubic;

import com.aurify.fixclient.canonical.enums.CanonicalSide;
import com.aurify.fixclient.canonical.event.CanonicalMarketDataRequest;
import com.aurify.fixclient.canonical.event.CanonicalOrderRequest;
import com.aurify.fixclient.canonical.event.CanonicalOutboundRequest;
import com.aurify.fixclient.provider.OutboundPolicy;
import org.springframework.stereotype.Component;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.fix43.MarketDataRequest;
import quickfix.fix43.NewOrderSingle;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Builds outbound FXCubic FIX 4.3 messages from canonical requests.
 *  Every field mandated by the FXCubic spec (HandInst=1, TimeInForce=IOC,
 *  ClOrdLinkID format) is hardcoded here and nowhere else. */
@Component
public class FxCubicOutgoingBuilder {

    private final FxCubicSymbolNormalizer symbolNormalizer;

    public FxCubicOutgoingBuilder(FxCubicSymbolNormalizer symbolNormalizer) {
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
     * FXCubic spec: MarketDataRequest asks for both Bid(0) and Offer(1),
     * MarketDepth=1 (top of book), one symbol per request recommended.
     */
    private MarketDataRequest buildMarketDataRequest(CanonicalMarketDataRequest request) {
        char subscriptionType = request.getSubscriptionRequestType()
                        == CanonicalMarketDataRequest.SubscriptionRequestType.UNSUBSCRIBE
                ? SubscriptionRequestType.DISABLE_PREVIOUS_SNAPSHOT_UPDATE_REQUEST
                : SubscriptionRequestType.SNAPSHOT_UPDATES;

        MarketDataRequest mdr = new MarketDataRequest(
                new MDReqID(request.getMdReqId()),
                new SubscriptionRequestType(subscriptionType),
                new MarketDepth(request.getMarketDepth() > 0 ? request.getMarketDepth() : 1));

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
        // FXCubic spec: HandInst is always "1" - set once, in the constructor
        NewOrderSingle nos = new NewOrderSingle(
                new ClOrdID(order.getClOrdId()),
                new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION),
                order.getSide() == CanonicalSide.BUY
                        ? new Side(Side.BUY) : new Side(Side.SELL),
                new TransactTime(utcNow()),
                order.getOrdType() == CanonicalOrderRequest.OrdType.LIMIT
                        ? new OrdType(OrdType.LIMIT) : new OrdType(OrdType.MARKET)
        );
        nos.set(new Symbol(symbolNormalizer.normalize(order.getSymbol(), policy)));
        nos.set(new OrderQty(order.getOrderQty().doubleValue()));
        // FXCubic spec: TimeInForce is always Immediate-Or-Cancel
        nos.set(new TimeInForce(TimeInForce.IMMEDIATE_OR_CANCEL));
        if (order.getOrdType() == CanonicalOrderRequest.OrdType.LIMIT) {
            nos.set(new Price(order.getPrice().doubleValue()));
        }
        if (order.getAccount() != null) {
            nos.set(new Account(order.getAccount()));
        }
        // tag 583: mandatory "ticket-account-group" format, all three populated
        nos.setString(583, buildClOrdLinkId(order));
        return nos;
    }

    private String buildClOrdLinkId(CanonicalOrderRequest order) {
        String ticket = order.getTicketId() != null ? order.getTicketId() : "0";
        String account = order.getAccount() != null ? order.getAccount() : "0";
        String group = order.getGroup() != null ? order.getGroup() : "Default";
        return ticket + "-" + account + "-" + group;
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
