package com.aurify.fixclient.dispatch;

import com.aurify.fixclient.canonical.event.CanonicalOutboundRequest;
import com.aurify.fixclient.provider.OutboundPolicy;
import quickfix.SessionID;

/**
 * A canonical request plus the routing and policy context needed to send it.
 *
 * The canonical request stays provider-neutral and credential-free; everything
 * that says *where* it goes and *what limits apply* travels alongside it here.
 */
public record OutboundDispatchEnvelope(
        CanonicalOutboundRequest request,
        OutboundPolicy policy,
        SessionID sessionId,
        String lpAccountId
) {
}
