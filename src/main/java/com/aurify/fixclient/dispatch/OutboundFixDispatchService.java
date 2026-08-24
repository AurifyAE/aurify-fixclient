package com.aurify.fixclient.dispatch;

import com.aurify.fixclient.canonical.event.CanonicalOutboundRequest;
import com.aurify.fixclient.events.GatewayEventPublisher;
import com.aurify.fixclient.persistence.PersistenceGateway;
import com.aurify.fixclient.provider.LiquidityProviderAdapter;
import com.aurify.fixclient.provider.ProviderAdapterRegistry;
import com.aurify.fixclient.provider.ValidationResult;
import com.aurify.fixclient.session.ProviderSessionRegistry;
import com.aurify.fixclient.session.SessionRole;
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

/** Resolves adapter + session, validates, builds the FIX message, and sends
 *  it - all business logic stays inside the resolved adapter, never here. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboundFixDispatchService {

    private final ProviderAdapterRegistry adapterRegistry;
    private final ProviderSessionRegistry sessionRegistry;
    private final PersistenceGateway persistenceGateway;
    private final GatewayEventPublisher eventPublisher;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public void dispatch(CanonicalOutboundRequest request) {
        try {
            dispatchOrThrow(request);
        } catch (RuntimeException e) {
            log.error("Outbound dispatch failed for provider {}", request.provider(), e);
            persistenceGateway.persistFailedPublish(null, e);
        }
    }

    /** Synchronous send path used by request/response adapters such as gRPC. */
    public void dispatchOrThrow(CanonicalOutboundRequest request) {
        Optional<LiquidityProviderAdapter> adapterOpt = adapterRegistry.resolve(request.provider());
        if (adapterOpt.isEmpty()) {
            throw new IllegalStateException("No adapter for provider " + request.provider());
        }
        LiquidityProviderAdapter adapter = adapterOpt.get();

        ValidationResult validation = adapter.validateOutbound(request);
        if (!validation.isValid()) {
            throw new IllegalArgumentException("Outbound request rejected: " + validation.getErrors());
        }

        Optional<SessionID> sessionOpt = sessionRegistry.resolve(request.provider(), SessionRole.TRADING);
        if (sessionOpt.isEmpty()) {
            throw new IllegalStateException("No TRADING session available for provider " + request.provider());
        }
        SessionID sessionId = sessionOpt.get();

        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(request.provider() + "-outbound");
        breaker.executeRunnable(() -> send(adapter, request, sessionId));
    }

    private void send(LiquidityProviderAdapter adapter, CanonicalOutboundRequest request, SessionID sessionId) {
        Message fixMessage = adapter.buildOutgoing(request, sessionId);
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
