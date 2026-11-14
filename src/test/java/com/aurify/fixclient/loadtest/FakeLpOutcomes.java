package com.aurify.fixclient.loadtest;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What the fake LP does with one order. Pure, so the rules a load run depends
 * on are tested on their own.
 *
 * The order of the rules is the point: a random reject comes first, then a
 * limit the market has not reached (a real venue would not trade it, whatever
 * the mix says), then the configured mix.
 */
final class FakeLpOutcomes {

    enum Kind { FILL, PARTIAL, NO_FILL, REJECT }

    record Order(boolean buy, boolean limit, BigDecimal limitPrice, BigDecimal qty) {}

    /** {@code filledQty} is what traded; {@code price} is the execution price. */
    record Decision(Kind kind, BigDecimal filledQty, BigDecimal price, String text) {}

    private FakeLpOutcomes() {
    }

    /**
     * @param roll a uniform random number in [0, 100) that picks from the mix
     */
    static Decision decide(Order order, BigDecimal marketPrice, double roll, FakeLpConfig config) {
        if (roll < config.rejectPct()) {
            return reject("Fake LP: random reject");
        }
        if (order.limit() && !marketable(order, marketPrice)) {
            return noFill(config, "Fake LP: limit " + order.limitPrice().toPlainString()
                    + " not reached, market " + marketPrice.toPlainString());
        }

        double fillUpTo = config.rejectPct() + config.fillPct();
        double partialUpTo = fillUpTo + config.partialPct();
        if (roll < fillUpTo) {
            return fill(order, marketPrice);
        }
        if (roll < partialUpTo) {
            return partial(order, marketPrice, config);
        }
        return noFill(config, "Fake LP: no liquidity");
    }

    /** A buy trades at or below its limit, a sell at or above it. */
    static boolean marketable(Order order, BigDecimal marketPrice) {
        int compared = order.limitPrice().compareTo(marketPrice);
        return order.buy() ? compared >= 0 : compared <= 0;
    }

    private static Decision fill(Order order, BigDecimal price) {
        return new Decision(Kind.FILL, order.qty(), price, null);
    }

    private static Decision partial(Order order, BigDecimal price, FakeLpConfig config) {
        // FXCubic cannot report a partial: the gateway would never see the order close.
        if (!config.profile().partialFills) {
            return fill(order, price);
        }
        int scale = Math.max(order.qty().scale(), 0);
        BigDecimal filled = order.qty().multiply(BigDecimal.valueOf(config.partialRatio()))
                .setScale(scale, RoundingMode.DOWN);
        // An order too small to split trades whole, as at a real venue.
        if (filled.signum() <= 0 || filled.compareTo(order.qty()) >= 0) {
            return fill(order, price);
        }
        return new Decision(Kind.PARTIAL, filled, price, null);
    }

    /** FXCubic has no cancelled status the gateway maps, so a no-fill there is a reject. */
    private static Decision noFill(FakeLpConfig config, String text) {
        return config.profile().partialFills
                ? new Decision(Kind.NO_FILL, BigDecimal.ZERO, null, text)
                : reject(text);
    }

    private static Decision reject(String text) {
        return new Decision(Kind.REJECT, BigDecimal.ZERO, null, text);
    }
}
