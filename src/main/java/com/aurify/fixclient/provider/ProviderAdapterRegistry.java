package com.aurify.fixclient.provider;

import com.aurify.fixclient.session.LpSessionEntry;
import com.aurify.fixclient.session.LpSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Resolves the correct adapter by provider name or by session, so callers
 *  never hardcode "if provider == X" branching. */
@Component
@RequiredArgsConstructor
public class ProviderAdapterRegistry {

    private final List<LiquidityProviderAdapter> adapters;
    private final LpSessionRegistry sessionRegistry;

    private Map<String, LiquidityProviderAdapter> byName;

    private Map<String, LiquidityProviderAdapter> index() {
        if (byName == null) {
            byName = adapters.stream()
                    .collect(Collectors.toMap(LiquidityProviderAdapter::providerName, a -> a));
        }
        return byName;
    }

    public Optional<LiquidityProviderAdapter> resolve(String providerName) {
        return Optional.ofNullable(index().get(providerName));
    }

    public Optional<LiquidityProviderAdapter> resolveForSession(SessionID sessionId) {
        return sessionRegistry.findBySessionId(sessionId)
                .map(LpSessionEntry::provider)
                .flatMap(this::resolve);
    }
}
