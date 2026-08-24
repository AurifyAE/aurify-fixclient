package com.aurify.fixclient.provider.fxcubic;

import com.aurify.fixclient.canonical.event.CanonicalEvent;
import com.aurify.fixclient.canonical.event.CanonicalOrderRequest;
import com.aurify.fixclient.canonical.event.CanonicalOutboundRequest;
import com.aurify.fixclient.events.GatewayEventPublisher;
import com.aurify.fixclient.provider.AbstractQuickFixProviderAdapter;
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
 * FXCubic FIX API v1.2.2 (FIX.4.3).
 * Encodes every LP-specific rule from the spec so it never leaks into shared code:
 *  - dual session (pricing + trading), pricing session must ResetSeqNumFlag=Y
 *  - order types: Market(1) / Limit(2) only, TimeInForce is always IOC(3)
 *  - HandInst always "1"
 *  - ClOrdLinkID (583) mandatory, format "ticket-account-group"
 *  - symbol format is Maker-preference, delegated to FxCubicSymbolNormalizer
 */
@Component
public class FxCubicProviderAdapter extends AbstractQuickFixProviderAdapter {

    public static final String PROVIDER_NAME = "fxcubic";

    private final FxCubicIncomingMapper incomingMapper;
    private final FxCubicOutgoingBuilder outgoingBuilder;
    private final FxCubicStartupWorkflow startupWorkflow;
    private final FxCubicSymbolNormalizer symbolNormalizer;

    public FxCubicProviderAdapter(GatewayEventPublisher eventPublisher,
                                   FxCubicIncomingMapper incomingMapper,
                                   FxCubicOutgoingBuilder outgoingBuilder,
                                   FxCubicStartupWorkflow startupWorkflow,
                                   FxCubicSymbolNormalizer symbolNormalizer) {
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
                .supportedTimeInForce(Set.of("IOC"))
                .build();
    }

    @Override
    public CanonicalEvent mapIncoming(Message fixMessage, SessionID sessionId) throws FieldNotFound {
        return incomingMapper.map(fixMessage, sessionId);
    }

    @Override
    public Message buildOutgoing(CanonicalOutboundRequest request, SessionID sessionId) {
        Message fixMessage = outgoingBuilder.build(request, sessionId);
        applyStandardHeader(fixMessage);
        return fixMessage;
    }

    @Override
    public void onPostLogonStartup(SessionID sessionId, DirectSessionControlService sessionControl) {
        startupWorkflow.run(sessionId);
    }

    @Override
    public ValidationResult validateOutbound(CanonicalOutboundRequest request) {
        if (!(request instanceof CanonicalOrderRequest order)) {
            return ValidationResult.ok();
        }
        if (order.getTimeInForce() != CanonicalOrderRequest.TimeInForce.IOC) {
            return ValidationResult.reject("FXCubic only supports TimeInForce=IOC");
        }
        if (order.getOrdType() == CanonicalOrderRequest.OrdType.LIMIT
                && (order.getPrice() == null || order.getPrice().compareTo(BigDecimal.ZERO) <= 0)) {
            return ValidationResult.reject("Price is required for Limit orders");
        }
        return ValidationResult.ok();
    }

    @Override
    public String normalizeSymbol(String rawSymbol) {
        return symbolNormalizer.normalize(rawSymbol);
    }
}
