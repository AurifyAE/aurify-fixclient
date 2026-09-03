package com.aurify.fixclient.provider.finalto;

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
 * Finalto (CFH) FIX API v2.6 (FIX.4.4).
 * Encodes every LP-specific rule from the spec so it never leaks into shared code:
 *  - order types: Market(1) / Limit(2); TimeInForce is IOC(3) or FOK(4)
 *  - NoPartyIDs/PartyID/PartyIDSource=D/PartyRole=3 mandatory on NewOrderSingle
 *  - no HandlInst (Market/Limit only, never Stop/StopLimit) and no ClOrdLinkID
 *    (that tag does not exist in this spec)
 *  - all custom tags (5001 MarkUp, 5003 Track) are in the user-defined range,
 *    so no forked data dictionary is needed - just relaxed validation
 */
@Component
public class FinaltoProviderAdapter extends AbstractQuickFixProviderAdapter {

    public static final String PROVIDER_NAME = "finalto";

    private final FinaltoIncomingMapper incomingMapper;
    private final FinaltoOutgoingBuilder outgoingBuilder;
    private final FinaltoStartupWorkflow startupWorkflow;
    private final FinaltoSymbolNormalizer symbolNormalizer;

    public FinaltoProviderAdapter(GatewayEventPublisher eventPublisher,
                                   FinaltoIncomingMapper incomingMapper,
                                   FinaltoOutgoingBuilder outgoingBuilder,
                                   FinaltoStartupWorkflow startupWorkflow,
                                   FinaltoSymbolNormalizer symbolNormalizer) {
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
                .fixVersion("FIX.4.4")
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
            return ValidationResult.reject("Finalto only supports TimeInForce=IOC or FOK");
        }
        if (order.getOrdType() == CanonicalOrderRequest.OrdType.LIMIT
                && (order.getPrice() == null || order.getPrice().compareTo(BigDecimal.ZERO) <= 0)) {
            return ValidationResult.reject("Price is required for Limit orders");
        }
        // The gateway is the last gate before the LP: enforce the caller's own
        // limits here too, rather than trusting that they were checked upstream.
        if (order.getOrderQty() != null && policy.exceedsMaxOrderSize(order.getOrderQty().longValue())) {
            return ValidationResult.reject(
                    "Order quantity " + order.getOrderQty() + " exceeds maxOrderSize " + policy.maxOrderSize());
        }
        if (order.getSymbol() != null && !policy.allowsSymbol(order.getSymbol().trim().toUpperCase())) {
            return ValidationResult.reject(
                    "Symbol " + order.getSymbol() + " is not allowed for this LP account");
        }
        return ValidationResult.ok();
    }

    @Override
    public String normalizeSymbol(String rawSymbol, OutboundPolicy policy) {
        return symbolNormalizer.normalize(rawSymbol, policy);
    }

    /**
     * Finalto's custom tags (5001 MarkUp, 5003 Track) are both in the
     * user-defined range (>= 5000), so relaxing validation instead of forking
     * a dictionary is enough - the stock FIX44.xml is used unchanged.
     */
    @Override
    public boolean validateUserDefinedFields() {
        return false;
    }
}
