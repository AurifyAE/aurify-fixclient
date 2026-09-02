package com.aurify.fixclient.provider;

import java.util.Set;

/**
 * The per-order limits and symbol allowlist an adapter must enforce, carved out
 * of the caller's session spec.
 *
 * Deliberately excludes host, comp IDs and credentials: adapters decide what
 * goes into a FIX message, and nothing here may ever end up in one.
 */
public record OutboundPolicy(
        Set<String> allowedSymbols,
        long maxOrderSize,
        double maxPositionPerSymbol,
        double maxTotalExposure
) {

    public OutboundPolicy {
        allowedSymbols = allowedSymbols == null ? Set.of() : Set.copyOf(allowedSymbols);
    }

    /** No caller-supplied limits - used by internal paths that carry no spec. */
    public static OutboundPolicy unrestricted() {
        return new OutboundPolicy(Set.of(), 0L, 0D, 0D);
    }

    /** An empty allowlist means "the caller declared none", not "reject everything". */
    public boolean allowsSymbol(String symbol) {
        return allowedSymbols.isEmpty() || allowedSymbols.contains(symbol);
    }

    public boolean exceedsMaxOrderSize(long quantity) {
        return maxOrderSize > 0 && quantity > maxOrderSize;
    }
}
