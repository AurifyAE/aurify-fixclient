package com.yourorg.fixgateway.session;

import org.springframework.stereotype.Component;
import quickfix.SessionID;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class InMemoryProviderSessionRegistry implements ProviderSessionRegistry {

    private record Key(String provider, SessionRole role) {}

    private final Map<Key, SessionID> byProviderRole = new ConcurrentHashMap<>();
    private final Map<SessionID, String> sessionToProvider = new ConcurrentHashMap<>();

    @Override
    public Optional<SessionID> resolve(String providerName, SessionRole role) {
        return Optional.ofNullable(byProviderRole.get(new Key(providerName, role)));
    }

    @Override
    public Optional<SessionID> resolveByCompIds(String senderCompId, String targetCompId) {
        return byProviderRole.values().stream()
                .filter(s -> s.getSenderCompID().equals(senderCompId) && s.getTargetCompID().equals(targetCompId))
                .findFirst();
    }

    @Override
    public Optional<String> providerNameOf(SessionID sessionId) {
        return Optional.ofNullable(sessionToProvider.get(sessionId));
    }

    @Override
    public List<SessionID> allSessionsFor(String providerName) {
        return byProviderRole.entrySet().stream()
                .filter(e -> e.getKey().provider().equals(providerName))
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
    }

    @Override
    public void register(String providerName, SessionRole role, SessionID sessionId) {
        byProviderRole.put(new Key(providerName, role), sessionId);
        sessionToProvider.put(sessionId, providerName);
    }
}
