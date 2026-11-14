package com.aurify.fixclient.provider.stonex;

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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Builds outbound StoneX FIX 4.3 messages, per the Integral FX Inside FIX
 * Client API 5.1 (page numbers below are that document's).
 *
 * NewOrderSingle (4.3.1, pp.88-92) requires fields neither other venue takes:
 *  - Currency(15): the dealt currency; here always the base of the pair, so
 *    OrderQty is in base units (troy ounces for XAU/USD).
 *  - ExecInst(18)="B" (OK to cross). Without B the order only waits for other
 *    participants to hit it, and a market order cannot be a displayed order
 *    (p.29), so it would never fill.
 *  - MinQty(110): equal to OrderQty for FOK, 0 for IOC (any fill size).
 *  - Product(460)=4 (CURRENCY), SecurityType(167)=FOR.
 * QuoteID(117) is required only for previously-quoted (OrdType=D) orders,
 * which this gateway does not send.
 *
 * SenderSubID(50) is not set here: it belongs on every message of the
 * session, Logon included, so it comes from the SessionID.
 */
@Component
public class StoneXOutgoingBuilder {

    static final String EXEC_INST_OK_TO_CROSS = "B";
    static final String SECURITY_TYPE_FX = "FOR";
    static final int PRODUCT_CURRENCY = 4;

    private final StoneXSymbolNormalizer symbolNormalizer;

    public StoneXOutgoingBuilder(StoneXSymbolNormalizer symbolNormalizer) {
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
     * MarketDataRequest (3.3.1, pp.71-74): one symbol per request, Bid and
     * Offer both listed (the venue ignores them but FIX requires them),
     * Product=4 inside the symbol group - omitting it gets the request
     * rejected - and DeliverToCompID(128) naming the provider (StoneX: BRKD).
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
            related.set(new Symbol(StoneXSymbolNormalizer.toWire(symbol)));
            related.set(new Product(PRODUCT_CURRENCY));
            mdr.addGroup(related);
        }
        setDeliverTo(mdr, request.getDeliverToCompId());
        return mdr;
    }

    private NewOrderSingle buildNewOrderSingle(CanonicalOrderRequest order, OutboundPolicy policy) {
        // HandlInst is optional here (p.89) but "1" is what the spec says it
        // means, and the FIX 4.3 NewOrderSingle constructor wants it anyway.
        NewOrderSingle nos = new NewOrderSingle(
                new ClOrdID(order.getClOrdId()),
                new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION),
                order.getSide() == CanonicalSide.BUY ? new Side(Side.BUY) : new Side(Side.SELL),
                new TransactTime(utcNow()),
                order.getOrdType() == CanonicalOrderRequest.OrdType.LIMIT
                        ? new OrdType(OrdType.LIMIT) : new OrdType(OrdType.MARKET));

        String symbol = symbolNormalizer.normalize(order.getSymbol(), policy);
        nos.set(new Symbol(symbol));
        nos.set(new Product(PRODUCT_CURRENCY));
        nos.set(new SecurityType(SECURITY_TYPE_FX));
        String base = StoneXSymbolNormalizer.baseCurrency(symbol);
        if (base == null) {
            throw new IllegalArgumentException(
                    "StoneX needs a base/term pair to name the dealt currency, got '" + symbol + "'");
        }
        nos.set(new Currency(base));

        BigDecimal qty = order.getOrderQty();
        nos.set(new OrderQty(qty.doubleValue()));
        boolean fok = order.getTimeInForce() == CanonicalOrderRequest.TimeInForce.FOK;
        nos.set(new TimeInForce(fok ? TimeInForce.FILL_OR_KILL : TimeInForce.IMMEDIATE_OR_CANCEL));
        nos.set(new MinQty(fok ? qty.doubleValue() : 0d));
        nos.set(new ExecInst(EXEC_INST_OK_TO_CROSS));

        if (order.getOrdType() == CanonicalOrderRequest.OrdType.LIMIT) {
            nos.set(new Price(order.getPrice().doubleValue()));
        }
        if (order.getAccount() != null) {
            nos.set(new Account(order.getAccount()));
        }
        setDeliverTo(nos, order.getDeliverToCompId());
        return nos;
    }

    /** DeliverToCompID is a header field; optional on orders (it restricts
     *  matching to that provider), required on market data. */
    private static void setDeliverTo(Message message, String deliverToCompId) {
        if (deliverToCompId != null && !deliverToCompId.isBlank()) {
            message.getHeader().setString(DeliverToCompID.FIELD, deliverToCompId.trim());
        }
    }

    /** TransactTime (60) must be UTC - see FxCubicOutgoingBuilder#utcNow. */
    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
