package com.aurify.fixclient.dispatch;

import com.aurify.fixclient.canonical.event.CanonicalOutboundRequest;
import com.aurify.fixclient.persistence.PersistenceGateway;
import com.aurify.fixclient.provider.LiquidityProviderAdapter;
import com.aurify.fixclient.provider.OutboundPolicy;
import com.aurify.fixclient.provider.ProviderAdapterRegistry;
import com.aurify.fixclient.provider.ValidationResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;

import java.util.Optional;

/** Validates, builds the FIX message, and sends it on a session the caller has
 *  already established - all business logic stays inside the resolved adapter,
 *  never here. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboundFixDispatchService {

    private final ProviderAdapterRegistry adapterRegistry;
    private final PersistenceGateway persistenceGateway;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public void dispatch(CanonicalOutboundRequest request, OutboundPolicy policy,
                         SessionID sessionId, String lpAccountId) {
        try {
            dispatchOrThrow(request, policy, sessionId, lpAccountId);
        } catch (RuntimeException e) {
            log.error("Outbound dispatch failed for LP account {}", lpAccountId, e);
            persistenceGateway.persistFailedPublish(null, e);
        }
    }

    /** Synchronous send path used by request/response adapters such as gRPC. */
    public void dispatchOrThrow(CanonicalOutboundRequest request, OutboundPolicy policy,
                                SessionID sessionId, String lpAccountId) {
        Optional<LiquidityProviderAdapter> adapterOpt = adapterRegistry.resolve(request.provider());
        if (adapterOpt.isEmpty()) {
            throw new IllegalStateException("No adapter for provider " + request.provider());
        }
        LiquidityProviderAdapter adapter = adapterOpt.get();

        ValidationResult validation = adapter.validateOutbound(request, policy);
        if (!validation.isValid()) {
            throw new IllegalArgumentException("Outbound request rejected: " + validation.getErrors());
        }

        // Keyed per LP account, not per provider: one LP going bad must not trip
        // the breaker for every other account on the same provider.
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(lpAccountId + "-outbound");
        breaker.executeRunnable(() -> send(adapter, request, policy, sessionId));
    }

    private void send(LiquidityProviderAdapter adapter, CanonicalOutboundRequest request,
                      OutboundPolicy policy, SessionID sessionId) {
        Message fixMessage = adapter.buildOutgoing(request, policy, sessionId);
        try {
            boolean sent = Session.sendToTarget(fixMessage, sessionId);
            if (sent) {
                persistenceGateway.persistRawOutbound(sessionId, fixMessage.toString());
            } else {
                throw new IllegalStateException("Session.sendToTarget returned false for " + sessionId);
            }
        } catch (SessionNotFound e) {
            throw new IllegalStateException("Session not found for outbound dispatch: " + sessionId, e);
        }
    }
}
