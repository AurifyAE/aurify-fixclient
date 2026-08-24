package com.yourorg.fixgateway.provider;

import com.yourorg.fixgateway.canonical.event.CanonicalEvent;
import com.yourorg.fixgateway.canonical.event.CanonicalOutboundRequest;
import com.yourorg.fixgateway.session.DirectSessionControlService;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;

public interface LiquidityProviderAdapter {

    String providerName();

    ProviderCapabilities capabilities();

    /** Raw FIX -> canonical event. Called from the MessageCracker's typed handlers. */
    CanonicalEvent mapIncoming(Message fixMessage, SessionID sessionId) throws FieldNotFound;

    /** Canonical outbound request -> provider-specific FIX message. Called late,
     *  only inside the outbound dispatch pipeline. */
    Message buildOutgoing(CanonicalOutboundRequest request, SessionID sessionId);

    /** Invoked once per session immediately after a successful Logon. */
    void onPostLogonStartup(SessionID sessionId, DirectSessionControlService sessionControl);

    /** LP-specific structural/business validation before an outbound send. */
    ValidationResult validateOutbound(CanonicalOutboundRequest request);

    /** Symbol format is "Maker preference" per spec - never assume a shared format. */
    String normalizeSymbol(String rawSymbol);
}
