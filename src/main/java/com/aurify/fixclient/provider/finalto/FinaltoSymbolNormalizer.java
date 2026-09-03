package com.aurify.fixclient.provider.finalto;

import com.aurify.fixclient.provider.OutboundPolicy;
import org.springframework.stereotype.Component;

/**
 * The gateway keeps no symbol list of its own for any provider - the caller's
 * allowlist (from the LP account's own configuration) is authoritative. Same
 * approach as {@code FxCubicSymbolNormalizer}; Finalto's spec gives no reason
 * to diverge from it.
 */
@Component
public class FinaltoSymbolNormalizer {

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
