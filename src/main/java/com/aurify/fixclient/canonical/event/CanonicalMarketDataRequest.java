package com.aurify.fixclient.canonical.event;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/** Outbound MarketDataRequest (35=V), provider-agnostic. Converted to FIX
 *  late, inside the resolved provider adapter's buildOutgoing() - the same
 *  pattern as CanonicalOrderRequest. */
@Value
@Builder
public class CanonicalMarketDataRequest implements CanonicalOutboundRequest {
    String provider;
    String mdReqId;
    List<String> symbols;
    SubscriptionRequestType subscriptionRequestType;
    int marketDepth;

    /** Mirrors FIX tag 263 (SubscriptionRequestType). SNAPSHOT (0) is
     *  intentionally omitted - the gateway only ever wants live updates or to
     *  stop them. */
    public enum SubscriptionRequestType { SNAPSHOT_PLUS_UPDATES, UNSUBSCRIBE }

    @Override
    public String provider() { return provider; }
}
