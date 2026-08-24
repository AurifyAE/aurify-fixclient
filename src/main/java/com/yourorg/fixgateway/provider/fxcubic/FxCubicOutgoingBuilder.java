package com.yourorg.fixgateway.provider.fxcubic;

import com.yourorg.fixgateway.canonical.event.CanonicalOrderRequest;
import com.yourorg.fixgateway.canonical.event.CanonicalOutboundRequest;
import org.springframework.stereotype.Component;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.fix43.NewOrderSingle;

import java.time.LocalDateTime;
import java.util.Date;

/** Builds outbound FXCubic FIX 4.3 messages from canonical requests.
 *  Every field mandated by the FXCubic spec (HandInst=1, TimeInForce=IOC,
 *  ClOrdLinkID format) is hardcoded here and nowhere else. */
@Component
public class FxCubicOutgoingBuilder {

    private final FxCubicSymbolNormalizer symbolNormalizer;

    public FxCubicOutgoingBuilder(FxCubicSymbolNormalizer symbolNormalizer) {
        this.symbolNormalizer = symbolNormalizer;
    }

    public Message build(CanonicalOutboundRequest request, SessionID sessionId) {
        if (request instanceof CanonicalOrderRequest order) {
            return buildNewOrderSingle(order);
        }
        throw new IllegalArgumentException("Unsupported outbound request type: " + request.getClass());
    }

    private NewOrderSingle buildNewOrderSingle(CanonicalOrderRequest order) {
        NewOrderSingle nos = new NewOrderSingle(
                new ClOrdID(order.getClOrdId()),
                new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION),
                order.getSide() == com.yourorg.fixgateway.canonical.enums.CanonicalSide.BUY
                        ? new Side(Side.BUY) : new Side(Side.SELL),
                new TransactTime(LocalDateTime.now()),
                order.getOrdType() == CanonicalOrderRequest.OrdType.LIMIT
                        ? new OrdType(OrdType.LIMIT) : new OrdType(OrdType.MARKET)
        );
        nos.set(new Symbol(symbolNormalizer.normalize(order.getSymbol())));
        nos.set(new OrderQty(order.getOrderQty().doubleValue()));
        // FXCubic spec: TimeInForce is always Immediate-Or-Cancel
        nos.set(new TimeInForce(TimeInForce.IMMEDIATE_OR_CANCEL));
        // FXCubic spec: HandInst is always "1"
        nos.set(new HandlInst((HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION)));
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
}
