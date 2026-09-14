package com.aurify.fixclient.canonical.enums;

/**
 * MDReqRejReason (281) in words.
 *
 * Not provider-specific - these are standard FIX values, which is why this
 * sits in canonical/ rather than in either provider package. It exists because
 * a venue is allowed to reject a subscription with the code alone and no
 * Text(58): CfhDemoPrices answers 281=3 and nothing else, so without this the
 * operator sees "reason 3" and has to go and look up what that means before
 * they can act on it - and the answer, "your LP account is not entitled to
 * market data", is one they can only fix by calling the venue.
 */
public final class MarketDataRejectReason {

    private MarketDataRejectReason() {
    }

    public static String describe(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        switch (code.trim().toUpperCase()) {
            case "0": return "Unknown symbol";
            case "1": return "Duplicate MDReqID";
            case "2": return "Insufficient bandwidth";
            case "3": return "Insufficient permissions - the LP account is not entitled to market data";
            case "4": return "Unsupported SubscriptionRequestType";
            case "5": return "Unsupported MarketDepth";
            case "6": return "Unsupported MDUpdateType";
            case "7": return "Unsupported AggregatedBook";
            case "8": return "Unsupported MDEntryType";
            case "9": return "Unsupported TradingSessionID";
            case "A": return "Unsupported Scope";
            case "B": return "Unsupported OpenCloseSettleFlag";
            case "C": return "Unsupported MDImplicitDelete";
            default: return null;
        }
    }
}
