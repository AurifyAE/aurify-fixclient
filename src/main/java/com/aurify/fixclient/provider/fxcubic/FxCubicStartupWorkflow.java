package com.aurify.fixclient.provider.fxcubic;

import com.aurify.fixclient.canonical.event.CanonicalMarketDataRequest;
import com.aurify.fixclient.dispatch.OutboundFixDispatchService;
import com.aurify.fixclient.provider.OutboundPolicy;
import com.aurify.fixclient.session.LpSessionEntry;
import com.aurify.fixclient.session.LpSessionRegistry;
import com.aurify.fixclient.session.SessionRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

import java.util.List;
import java.util.UUID;

/**
 * Post-logon behaviour for FXCubic.
 *
 * A TRADING session has nothing to do here. A PRICING session subscribes to
 * every symbol the caller's session spec allowed - taken from the
 * {@link LpSessionEntry} the session was created with, since this callback
 * only ever receives a {@code SessionID}. Firing on every logon (not just the
 * first) is deliberate: a reconnect loses whatever subscription state the
 * venue held for the dropped TCP session, so it must be re-requested every
 * time, not only once.
 *
 * Per FXCubic FIX API v1.2.2: MarketDataRequest asks for both Bid(0) and
 * Offer(1), MarketDepth=1 (top of book), one symbol per request.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FxCubicStartupWorkflow {

    private final LpSessionRegistry lpSessionRegistry;

    /**
     * Resolved on use, not on construction. Injecting the dispatch service
     * directly closes a bean cycle - dispatch needs ProviderAdapterRegistry,
     * which needs every adapter, which needs this workflow - and Spring
     * refuses to start the whole application because of it. An ObjectProvider
     * is a handle, not the bean, so construction stays acyclic while the call
     * site still gets the singleton.
     */
    private final ObjectProvider<OutboundFixDispatchService> dispatchService;

    public void run(SessionID sessionId) {
        lpSessionRegistry.findBySessionId(sessionId)
                .filter(entry -> entry.role() == SessionRole.PRICING)
                .ifPresentOrElse(this::subscribeAllowedSymbols,
                        () -> log.debug("Post-logon startup for {}: not a pricing session, nothing to do", sessionId));
    }

    private void subscribeAllowedSymbols(LpSessionEntry pricing) {
        if (pricing.allowedSymbols().isEmpty()) {
            log.warn("Pricing session for LP account {} has no allowed symbols - nothing to subscribe",
                    pricing.lpAccountId());
            return;
        }
        for (String symbol : pricing.allowedSymbols()) {
            subscribeOne(pricing, symbol);
        }
    }

    private void subscribeOne(LpSessionEntry pricing, String symbol) {
        CanonicalMarketDataRequest request = CanonicalMarketDataRequest.builder()
                .provider(FxCubicProviderAdapter.PROVIDER_NAME)
                .mdReqId(UUID.randomUUID().toString())
                .symbols(List.of(symbol))
                .subscriptionRequestType(CanonicalMarketDataRequest.SubscriptionRequestType.SNAPSHOT_PLUS_UPDATES)
                .marketDepth(1)
                .build();
        try {
            dispatchService.getObject().dispatchOrThrow(request,
                    new OutboundPolicy(pricing.allowedSymbols(), 0L, 0D, 0D),
                    pricing.sessionId(), pricing.lpAccountId());
            log.info("Subscribed to market data for LP account {} symbol {}", pricing.lpAccountId(), symbol);
        } catch (RuntimeException e) {
            log.warn("Failed to subscribe market data for LP account {} symbol {}",
                    pricing.lpAccountId(), symbol, e);
        }
    }
}
