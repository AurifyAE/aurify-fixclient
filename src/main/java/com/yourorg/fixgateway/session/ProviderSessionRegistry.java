package com.yourorg.fixgateway.session;

import quickfix.SessionID;

import java.util.List;
import java.util.Optional;

public interface ProviderSessionRegistry {
    Optional<SessionID> resolve(String providerName, SessionRole role);
    Optional<SessionID> resolveByCompIds(String senderCompId, String targetCompId);
    Optional<String> providerNameOf(SessionID sessionId);
    List<SessionID> allSessionsFor(String providerName);
    void register(String providerName, SessionRole role, SessionID sessionId);
}
