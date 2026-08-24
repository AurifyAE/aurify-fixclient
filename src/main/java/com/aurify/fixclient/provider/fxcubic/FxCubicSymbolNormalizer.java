package com.aurify.fixclient.provider.fxcubic;

import org.springframework.stereotype.Component;

import java.util.Set;

/** Symbol format is "Maker preference" per FXCubic spec - fail loudly on
 *  unrecognized symbols rather than silently passing them through, since a
 *  bad normalization here misroutes market data or orders. Confirm this
 *  list against your FXCubic onboarding docs before going live. */
@Component
public class FxCubicSymbolNormalizer {

    private static final Set<String> CONFIRMED_SYMBOLS = Set.of(
            "EURUSD", "GBPUSD", "USDJPY", "USDCHF", "AUDUSD", "USDCAD", "NZDUSD"
    );

    public String normalize(String rawSymbol) {
        String candidate = rawSymbol.trim().toUpperCase();
        if (!CONFIRMED_SYMBOLS.contains(candidate)) {
            throw new IllegalArgumentException(
                    "Symbol '" + rawSymbol + "' is not in the confirmed FXCubic symbol list; "
                            + "add it explicitly after confirming the exact format with FXCubic.");
        }
        return candidate;
    }
}
