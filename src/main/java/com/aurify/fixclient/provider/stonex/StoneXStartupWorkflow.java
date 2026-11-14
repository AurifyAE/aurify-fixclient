package com.aurify.fixclient.provider.stonex;

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
 * Post-logon behaviour for StoneX. Same shape as
 * {@link com.aurify.fixclient.provider.fxcubic.FxCubicStartupWorkflow}: a
 * TRADING session does nothing, a PRICING session subscribes to every symbol
 * the caller allowed, on every logon.
 *
 * The difference is DeliverToCompID(128), which FX Inside requires on every
 * MarketDataRequest (p.72): it is read from the session entry, because this
 * callback only ever receives a SessionID. Without it the request is not
 * addressed to the provider and no price arrives.
 *
 * MDReqID must not contain '@' (p.72); a UUID never does.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoneXStartupWorkflow {

    private final LpSessionRegistry lpSessionRegistry;

    /** Resolved on use, not on construction - see FxCubicStartupWorkflow for
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
        if (pricing.deliverToCompId() == null) {
            log.warn("Pricing session for LP account {} has no deliver_to_comp_id - StoneX requires it on "
                    + "MarketDataRequest, so expect no prices", pricing.lpAccountId());
        }
        for (String symbol : pricing.allowedSymbols()) {
            subscribeOne(pricing, symbol);
        }
    }

    private void subscribeOne(LpSessionEntry pricing, String symbol) {
        CanonicalMarketDataRequest request = CanonicalMarketDataRequest.builder()
                .provider(StoneXProviderAdapter.PROVIDER_NAME)
                .mdReqId(UUID.randomUUID().toString())
                .symbols(List.of(symbol))
                .subscriptionRequestType(CanonicalMarketDataRequest.SubscriptionRequestType.SNAPSHOT_PLUS_UPDATES)
                .marketDepth(1)
                .deliverToCompId(pricing.deliverToCompId())
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
