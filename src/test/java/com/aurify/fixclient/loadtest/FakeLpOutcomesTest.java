package com.aurify.fixclient.loadtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** The rules a load run depends on: which outcome an order gets, and what a profile can express. */
class FakeLpOutcomesTest {

    private static final BigDecimal MARKET = new BigDecimal("2345.00");

    private static FakeLpConfig config(FakeLpConfig.Profile profile) {
        return new FakeLpConfig(0, profile, 0, 0, 0, 85, 10, 3, 2, 0.6, MARKET, new BigDecimal("0.25"),
                1, 0, null, null, null);
    }

    private static FakeLpOutcomes.Order market(String qty) {
        return new FakeLpOutcomes.Order(true, false, null, new BigDecimal(qty));
    }

    private static FakeLpOutcomes.Order limit(boolean buy, String price) {
        return new FakeLpOutcomes.Order(buy, true, new BigDecimal(price), new BigDecimal("10"));
    }

    @Test
    void theMixIsTakenInOrderRejectFillPartialNoFill() {
        FakeLpConfig finalto = config(FakeLpConfig.Profile.FINALTO);

        assertEquals(FakeLpOutcomes.Kind.REJECT, FakeLpOutcomes.decide(market("10"), MARKET, 1.9, finalto).kind());
        assertEquals(FakeLpOutcomes.Kind.FILL, FakeLpOutcomes.decide(market("10"), MARKET, 2.0, finalto).kind());
        assertEquals(FakeLpOutcomes.Kind.FILL, FakeLpOutcomes.decide(market("10"), MARKET, 86.9, finalto).kind());
        assertEquals(FakeLpOutcomes.Kind.PARTIAL, FakeLpOutcomes.decide(market("10"), MARKET, 87.0, finalto).kind());
        assertEquals(FakeLpOutcomes.Kind.NO_FILL, FakeLpOutcomes.decide(market("10"), MARKET, 97.0, finalto).kind());
    }

    @Test
    void aPartialFillKeepsTheOrderQuantityScale() {
        FakeLpOutcomes.Decision partial = FakeLpOutcomes.decide(market("3.21"), MARKET, 90,
                config(FakeLpConfig.Profile.FINALTO));

        assertEquals(new BigDecimal("1.92"), partial.filledQty());
    }

    @Test
    void anOrderTooSmallToSplitTradesWhole() {
        FakeLpOutcomes.Decision decision = FakeLpOutcomes.decide(market("1"), MARKET, 90,
                config(FakeLpConfig.Profile.FINALTO));

        assertEquals(FakeLpOutcomes.Kind.FILL, decision.kind());
        assertEquals(new BigDecimal("1"), decision.filledQty());
    }

    @Test
    void aLimitTheMarketHasNotReachedNeverTrades() {
        FakeLpConfig finalto = config(FakeLpConfig.Profile.FINALTO);

        // A roll that would otherwise fill.
        assertEquals(FakeLpOutcomes.Kind.NO_FILL, FakeLpOutcomes.decide(limit(true, "2344.99"), MARKET, 50, finalto).kind());
        assertEquals(FakeLpOutcomes.Kind.NO_FILL, FakeLpOutcomes.decide(limit(false, "2345.01"), MARKET, 50, finalto).kind());
    }

    @Test
    void aMarketableLimitTradesAtTheMarketPrice() {
        FakeLpOutcomes.Decision buy = FakeLpOutcomes.decide(limit(true, "2346.00"), MARKET, 50,
                config(FakeLpConfig.Profile.FINALTO));

        assertEquals(FakeLpOutcomes.Kind.FILL, buy.kind());
        assertEquals(MARKET, buy.price());
        assertTrue(FakeLpOutcomes.marketable(limit(false, "2345.00"), MARKET), "a limit at the market trades");
    }

    @Test
    void fxcubicCannotPartFillOrCancelSoItFillsOrRejectsInstead() {
        FakeLpConfig fxcubic = config(FakeLpConfig.Profile.FXCUBIC);

        assertEquals(FakeLpOutcomes.Kind.FILL, FakeLpOutcomes.decide(market("10"), MARKET, 90, fxcubic).kind());
        assertEquals(FakeLpOutcomes.Kind.REJECT, FakeLpOutcomes.decide(market("10"), MARKET, 98, fxcubic).kind());
        assertEquals(FakeLpOutcomes.Kind.REJECT,
                FakeLpOutcomes.decide(limit(true, "2300.00"), MARKET, 50, fxcubic).kind());
    }

    @Test
    void aMixThatDoesNotAddUpTo100IsRefused() {
        assertThrows(IllegalArgumentException.class, () -> config(FakeLpConfig.Profile.FXCUBIC).withMix(90, 0, 0, 0));
    }
}
