package com.aurify.fixclient.provider.fxcubic;

import com.aurify.fixclient.provider.OutboundPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FxCubicSymbolNormalizerTest {

    private final FxCubicSymbolNormalizer normalizer = new FxCubicSymbolNormalizer();

    private static OutboundPolicy allowing(String... symbols) {
        return new OutboundPolicy(Set.of(symbols), 0L, 0D, 0D);
    }

    @Test
    void acceptsAMetalSymbolThatNoBuiltInFxPairListWouldHaveHeld() {
        // This is the case the previous hardcoded allowlist got wrong: the
        // system's own symbols are metals, not FX pairs.
        assertEquals("XAUUSD_1GRAM",
                normalizer.normalize("XAUUSD_1GRAM", allowing("XAUUSD_1GRAM")));
    }

    @Test
    void upperCasesAndTrimsBeforeMatching() {
        assertEquals("EURUSD", normalizer.normalize("  eurusd ", allowing("EURUSD")));
    }

    @Test
    void rejectsASymbolTheLpAccountDoesNotAllow() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize("GBPUSD", allowing("EURUSD")));
        assertTrue(e.getMessage().contains("GBPUSD"));
    }

    @Test
    void anEmptyAllowlistMeansTheCallerDeclaredNoneRatherThanRejectEverything() {
        assertEquals("EURUSD", normalizer.normalize("EURUSD", OutboundPolicy.unrestricted()));
    }

    @Test
    void rejectsABlankSymbol() {
        assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize("  ", OutboundPolicy.unrestricted()));
    }
}
