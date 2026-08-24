package com.yourorg.fixgateway.provider.fxcubic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.field.*;
import quickfix.fix43.MarketDataRequest;

import java.util.List;

/** Post-logon behavior for FXCubic: subscribe top-of-book market data on the
 *  PRICING session only. FXCubic recommends one symbol per MarketDataRequest. */
@Slf4j
@Component
public class FxCubicStartupWorkflow {

    // In production, source this from ProviderProperties (startup.symbols in YAML)
    private final List<String> subscriptionSymbols = List.of("EURUSD", "GBPUSD", "USDJPY");

    public void run(SessionID sessionId) {
        // Only the pricing session subscribes to market data - guard by session
        // qualifier/role lookup in the real implementation via ProviderSessionRegistry.
        for (String symbol : subscriptionSymbols) {
            sendMarketDataRequest(sessionId, symbol);
        }
    }

    private void sendMarketDataRequest(SessionID sessionId, String symbol) {
        MarketDataRequest request = new MarketDataRequest(
                new MDReqID(symbol + "-" + System.currentTimeMillis()),
                new SubscriptionRequestType(SubscriptionRequestType.SNAPSHOT_UPDATES),
                new MarketDepth(1) // top of book
        );

        MarketDataRequest.NoMDEntryTypes bid = new MarketDataRequest.NoMDEntryTypes();
        bid.set(new MDEntryType(MDEntryType.BID));
        request.addGroup(bid);

        MarketDataRequest.NoMDEntryTypes offer = new MarketDataRequest.NoMDEntryTypes();
        offer.set(new MDEntryType(MDEntryType.OFFER));
        request.addGroup(offer);

        MarketDataRequest.NoRelatedSym relatedSym = new MarketDataRequest.NoRelatedSym();
        relatedSym.set(new Symbol(symbol));
        request.addGroup(relatedSym);

        try {
            Session.sendToTarget(request, sessionId);
        } catch (SessionNotFound e) {
            log.error("Could not send startup MarketDataRequest for {} on {}", symbol, sessionId, e);
        }
    }
}
