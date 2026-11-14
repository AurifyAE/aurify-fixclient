package com.aurify.fixclient.provider.stonex;

import com.aurify.fixclient.provider.OutboundPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StoneXSymbolNormalizerTest {

    private final StoneXSymbolNormalizer normalizer = new StoneXSymbolNormalizer();

    private static OutboundPolicy allow(String... symbols) {
        return new OutboundPolicy(Set.of(symbols), 0L, 0D, 0D);
    }

    @Test
    void sixLetterPairsGetTheSlashFxInsideExpects() {
        assertEquals("XAU/USD", StoneXSymbolNormalizer.toWire("xauusd"));
        assertEquals("XAU/USD", StoneXSymbolNormalizer.toWire("XAU-USD"));
        assertEquals("EUR/USD", StoneXSymbolNormalizer.toWire("EUR/USD"));
    }

    @Test
    void anythingElseIsPassedThroughUntouched() {
        assertEquals("XAUUSD_1GRAM", StoneXSymbolNormalizer.toWire("xauusd_1gram"));
    }

    @Test
    void eitherSpellingInTheAllowlistMatchesEitherSpellingOnTheOrder() {
        assertEquals("XAU/USD", normalizer.normalize("XAUUSD", allow("XAU/USD")));
        assertEquals("XAU/USD", normalizer.normalize("XAU/USD", allow("XAUUSD")));
    }

    @Test
    void aSymbolNotAllowedIsRefused() {
        assertFalse(normalizer.isAllowed("EURUSD", allow("XAUUSD")));
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize("EURUSD", allow("XAUUSD")));
    }

    @Test
    void baseCurrencyIsOnlyDefinedForAPair() {
        assertEquals("XAU", StoneXSymbolNormalizer.baseCurrency("XAU/USD"));
        assertNull(StoneXSymbolNormalizer.baseCurrency("XAUUSD_1GRAM"));
    }
}
