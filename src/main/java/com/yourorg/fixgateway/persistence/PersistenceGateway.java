package com.yourorg.fixgateway.persistence;

import com.yourorg.fixgateway.canonical.event.CanonicalEvent;
import quickfix.SessionID;

/** Optional, pluggable. In-memory for dev, JDBC for production. Never called
 *  inline with FIX I/O - always from the async pipeline. */
public interface PersistenceGateway {
    void persistRawInbound(SessionID sessionId, String rawFix);
    void persistRawOutbound(SessionID sessionId, String rawFix);
    void persistCanonicalEvent(CanonicalEvent event);
    void persistFailedPublish(CanonicalEvent event, Throwable cause);
}
