package com.aurify.fixclient.provider;

import com.aurify.fixclient.canonical.event.CanonicalEvent;
import com.aurify.fixclient.canonical.event.CanonicalOutboundRequest;
import com.aurify.fixclient.session.DirectSessionControlService;
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
    Message buildOutgoing(CanonicalOutboundRequest request, OutboundPolicy policy, SessionID sessionId);

    /** Invoked once per session immediately after a successful Logon. */
    void onPostLogonStartup(SessionID sessionId, DirectSessionControlService sessionControl);

    /** LP-specific structural/business validation before an outbound send. The
     *  policy carries the caller's limits, so this is the last gate in front of
     *  the LP. */
    ValidationResult validateOutbound(CanonicalOutboundRequest request, OutboundPolicy policy);

    /** Symbol format is "Maker preference" per spec - never assume a shared
     *  format. The policy's allowlist is authoritative: the gateway keeps no
     *  symbol list of its own. */
    String normalizeSymbol(String rawSymbol, OutboundPolicy policy);

    /**
     * Data dictionary for this provider's sessions.
     *
     * Providers extend the standard enums - a reason code outside the FIX 4.3
     * list makes QuickFIX reject the whole message before the adapter ever
     * sees it - so a provider that needs a relaxed or extended dictionary
     * overrides this. Defaults to the stock dictionary for the FIX version.
     */
    default String dataDictionary(String fixVersion) {
        return fixVersion.replace(".", "") + ".xml";
    }

    /**
     * Whether the session's data dictionary must be able to name every custom
     * tag the provider sends. True (the default) is the safe choice - it is
     * QuickFIX's own default and matches a provider with no custom tags, or
     * one whose custom tags are already declared in a forked dictionary (e.g.
     * FXCubic's {@code FIX43-fxcubic.xml}, tag 583).
     *
     * A provider whose custom tags are all in the user-defined range
     * (>= 5000) can instead override this to {@code false} and skip
     * forking a dictionary entirely - QuickFIX accepts an undeclared
     * user-defined tag without complaint once validation is off for it.
     */
    default boolean validateUserDefinedFields() {
        return true;
    }
}
