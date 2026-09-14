package com.aurify.fixclient.provider.finalto;

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
 * Post-logon behaviour for Finalto (CFH). Mirrors
 * {@link com.aurify.fixclient.provider.fxcubic.FxCubicStartupWorkflow}
 * exactly: a TRADING session does nothing, a PRICING session subscribes to
 * every symbol the caller's session spec allowed.
 *
 * UNVERIFIED against the Finalto (CFH) FIX API v2.6 spec - no Finalto spec
 * document is checked into this repo. The request shape below (Bid+Offer,
 * MarketDepth=1, one symbol per request) is FXCubic's documented behaviour
 * applied here as a reasonable default; confirm it against the actual Finalto
 * spec before relying on this against a live Finalto pricing session.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinaltoStartupWorkflow {

    private final LpSessionRegistry lpSessionRegistry;

    /** Resolved on use, not on construction - see
     *  {@link com.aurify.fixclient.provider.fxcubic.FxCubicStartupWorkflow} for
     *  the bean cycle this avoids. */
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
                .provider(FinaltoProviderAdapter.PROVIDER_NAME)
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
