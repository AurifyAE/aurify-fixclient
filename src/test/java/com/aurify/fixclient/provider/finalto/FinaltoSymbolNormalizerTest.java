package com.aurify.fixclient.provider.finalto;

import com.aurify.fixclient.provider.OutboundPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FinaltoSymbolNormalizerTest {

    private final FinaltoSymbolNormalizer normalizer = new FinaltoSymbolNormalizer();

    @Test
    void uppercasesAndTrimsAnAllowedSymbol() {
        OutboundPolicy policy = new OutboundPolicy(Set.of("EURUSD"), 0L, 0D, 0D);
        assertEquals("EURUSD", normalizer.normalize(" eurusd ", policy));
    }

    @Test
    void rejectsASymbolOutsideTheAllowlist() {
        OutboundPolicy policy = new OutboundPolicy(Set.of("EURUSD"), 0L, 0D, 0D);
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize("GBPUSD", policy));
    }

    @Test
    void anEmptyAllowlistMeansNoneDeclaredNotRejectAll() {
        OutboundPolicy policy = new OutboundPolicy(Set.of(), 0L, 0D, 0D);
        assertEquals("XAUUSD", normalizer.normalize("xauusd", policy));
    }

    @Test
    void blankSymbolIsRejected() {
        OutboundPolicy policy = new OutboundPolicy(Set.of(), 0L, 0D, 0D);
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize("  ", policy));
    }
}
