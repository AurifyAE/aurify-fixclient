package com.aurify.fixclient.provider.fxcubic;

import com.aurify.fixclient.provider.OutboundPolicy;
import org.springframework.stereotype.Component;

/**
 * Symbol format is "Maker preference" per FXCubic spec, so the gateway cannot
 * know the valid set - it belongs to the LP account and arrives with the
 * request. This class therefore validates against the caller's allowlist and
 * never against a list of its own.
 *
 * That matters in practice: this system trades metal symbols such as
 * XAUUSD_1GRAM, which no built-in FX-pair list would ever have contained.
 */
@Component
public class FxCubicSymbolNormalizer {

    public String normalize(String rawSymbol, OutboundPolicy policy) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            throw new IllegalArgumentException("Symbol is required");
        }
        String candidate = rawSymbol.trim().toUpperCase();

        if (!policy.allowsSymbol(candidate)) {
            throw new IllegalArgumentException(
                    "Symbol '" + rawSymbol + "' is not in this LP account's allowed symbols "
                            + policy.allowedSymbols());
        }
        return candidate;
    }
}
