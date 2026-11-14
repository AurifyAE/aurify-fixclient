package com.aurify.fixclient.provider.stonex;

import com.aurify.fixclient.canonical.event.CanonicalEvent;
import com.aurify.fixclient.canonical.event.CanonicalOrderRequest;
import com.aurify.fixclient.canonical.event.CanonicalOutboundRequest;
import com.aurify.fixclient.events.GatewayEventPublisher;
import com.aurify.fixclient.provider.AbstractQuickFixProviderAdapter;
import com.aurify.fixclient.provider.OutboundPolicy;
import com.aurify.fixclient.provider.ProviderCapabilities;
import com.aurify.fixclient.provider.ValidationResult;
import com.aurify.fixclient.session.DirectSessionControlService;
import org.springframework.stereotype.Component;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;

import java.math.BigDecimal;
import java.util.Set;

/**
 * StoneX, over Integral's FX Inside FIX Client API 5.1 (FIX.4.3).
 * Encodes every venue-specific rule so it never leaks into shared code:
 *  - two sessions on one host: quote (pricing) and order (trading), same
 *    credentials, each with its own SenderCompID ("quote.X" / "order.X")
 *  - SenderSubID(50) on every message - the legal entity - carried by the
 *    SessionID (FixSession.sender_sub_id), not by this adapter
 *  - DeliverToCompID(128) naming the provider (LpSessionSpec.deliver_to_comp_id)
 *  - symbols as "BASE/TERM"; NewOrderSingle needs Currency, ExecInst, MinQty,
 *    Product and SecurityType (see StoneXOutgoingBuilder)
 *  - Market(1) / Limit(2) orders, TimeInForce IOC(3) or FOK(4)
 *
 * The ESP workflow (OrdType=D against a QuoteEntryID) is not implemented:
 * SubmitMarketOrder sends plain market orders, which FX Inside matches
 * against the subscribed providers itself (p.26).
 */
@Component
public class StoneXProviderAdapter extends AbstractQuickFixProviderAdapter {

    public static final String PROVIDER_NAME = "stonex";

    private final StoneXIncomingMapper incomingMapper;
    private final StoneXOutgoingBuilder outgoingBuilder;
    private final StoneXStartupWorkflow startupWorkflow;
    private final StoneXSymbolNormalizer symbolNormalizer;

    public StoneXProviderAdapter(GatewayEventPublisher eventPublisher,
                                 StoneXIncomingMapper incomingMapper,
                                 StoneXOutgoingBuilder outgoingBuilder,
                                 StoneXStartupWorkflow startupWorkflow,
                                 StoneXSymbolNormalizer symbolNormalizer) {
        super(eventPublisher);
        this.incomingMapper = incomingMapper;
        this.outgoingBuilder = outgoingBuilder;
        this.startupWorkflow = startupWorkflow;
        this.symbolNormalizer = symbolNormalizer;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return ProviderCapabilities.builder()
                .fixVersion("FIX.4.3")
                .supportsPricingSession(true)
                .supportsTradingSession(true)
                .supportedOrdTypes(Set.of("MARKET", "LIMIT"))
                .supportedTimeInForce(Set.of("IOC", "FOK"))
                .build();
    }

    @Override
    public CanonicalEvent mapIncoming(Message fixMessage, SessionID sessionId) throws FieldNotFound {
        return incomingMapper.map(fixMessage, sessionId);
    }

    @Override
    public Message buildOutgoing(CanonicalOutboundRequest request, OutboundPolicy policy, SessionID sessionId) {
        Message fixMessage = outgoingBuilder.build(request, policy, sessionId);
        applyStandardHeader(fixMessage);
        return fixMessage;
    }

    @Override
    public void onPostLogonStartup(SessionID sessionId, DirectSessionControlService sessionControl) {
        startupWorkflow.run(sessionId);
    }

    @Override
    public ValidationResult validateOutbound(CanonicalOutboundRequest request, OutboundPolicy policy) {
        if (!(request instanceof CanonicalOrderRequest order)) {
            return ValidationResult.ok();
        }
        if (order.getTimeInForce() != CanonicalOrderRequest.TimeInForce.IOC
                && order.getTimeInForce() != CanonicalOrderRequest.TimeInForce.FOK) {
            return ValidationResult.reject("StoneX hedge orders support TimeInForce=IOC or FOK only");
        }
        if (order.getOrdType() == CanonicalOrderRequest.OrdType.LIMIT
                && (order.getPrice() == null || order.getPrice().compareTo(BigDecimal.ZERO) <= 0)) {
            return ValidationResult.reject("Price is required for Limit orders");
        }
        if (order.getOrderQty() != null && policy.exceedsMaxOrderSize(order.getOrderQty())) {
            return ValidationResult.reject(
                    "Order quantity " + order.getOrderQty() + " exceeds maxOrderSize " + policy.maxOrderSize());
        }
        if (order.getSymbol() != null && !symbolNormalizer.isAllowed(order.getSymbol(), policy)) {
            return ValidationResult.reject(
                    "Symbol " + order.getSymbol() + " is not allowed for this LP account");
        }
        if (order.getSymbol() != null
                && StoneXSymbolNormalizer.baseCurrency(StoneXSymbolNormalizer.toWire(order.getSymbol())) == null) {
            return ValidationResult.reject(
                    "StoneX needs a currency-pair symbol such as XAU/USD, got " + order.getSymbol());
        }
        return ValidationResult.ok();
    }

    @Override
    public String normalizeSymbol(String rawSymbol, OutboundPolicy policy) {
        return symbolNormalizer.normalize(rawSymbol, policy);
    }

    /** ExecutionReport carries tags the stock FIX43.xml does not allow on it;
     *  see the header of FIX43-stonex.xml. */
    @Override
    public String dataDictionary(String fixVersion) {
        return "FIX.4.3".equals(fixVersion) ? "FIX43-stonex.xml" : super.dataDictionary(fixVersion);
    }

    /** Integral's extensions (7546, 7548, 7549, 7631, 9360-9366) are all in
     *  the user-defined range. */
    @Override
    public boolean validateUserDefinedFields() {
        return false;
    }
}
