package com.aurify.fixclient.provider.stonex;

import com.aurify.fixclient.provider.OutboundPolicy;
import org.springframework.stereotype.Component;

/**
 * StoneX (Integral FX Inside) names every pair "base/term" with a slash -
 * "EUR/USD", "XAU/USD" - on MarketDataRequest, NewOrderSingle and on what it
 * sends back (FX Inside FIX Client API 5.1, pp.73, 90).
 *
 * The rest of this system writes symbols without the slash, so the caller's
 * allowlist may hold either spelling. The allowlist stays authoritative, as
 * for every other venue; this class only decides the wire spelling and
 * compares the two forms by their letters, the same way LpQuoteRegistry
 * matches quotes back to subscribers.
 */
@Component
public class StoneXSymbolNormalizer {

    /** The wire symbol, validated against the caller's allowlist. */
    public String normalize(String rawSymbol, OutboundPolicy policy) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            throw new IllegalArgumentException("Symbol is required");
        }
        if (!isAllowed(rawSymbol, policy)) {
            throw new IllegalArgumentException(
                    "Symbol '" + rawSymbol + "' is not in this LP account's allowed symbols "
                            + policy.allowedSymbols());
        }
        return toWire(rawSymbol);
    }

    /** True when the allowlist is empty or holds this symbol in either spelling. */
    public boolean isAllowed(String rawSymbol, OutboundPolicy policy) {
        if (rawSymbol == null) {
            return false;
        }
        if (policy.allowedSymbols().isEmpty()) {
            return true;
        }
        String key = compact(rawSymbol);
        return policy.allowedSymbols().stream().anyMatch(allowed -> compact(allowed).equals(key));
    }

    /**
     * "xauusd" / "XAU-USD" / "XAU/USD" -> "XAU/USD". Anything that is not a
     * plain six-letter pair is passed through upper-cased, so a venue symbol
     * the operator configured verbatim is never mangled.
     */
    public static String toWire(String rawSymbol) {
        String upper = rawSymbol.trim().toUpperCase();
        if (upper.contains("/")) {
            return upper;
        }
        String letters = compact(upper);
        if (letters.length() == 6 && letters.chars().allMatch(Character::isLetter)) {
            return letters.substring(0, 3) + "/" + letters.substring(3);
        }
        return upper;
    }

    /** The dealt (base) currency of a wire symbol, or null if it has none. */
    public static String baseCurrency(String wireSymbol) {
        int slash = wireSymbol.indexOf('/');
        return slash == 3 ? wireSymbol.substring(0, 3) : null;
    }

    private static String compact(String symbol) {
        return symbol.replaceAll("[/_\\-\\s]", "").toUpperCase();
    }
}
