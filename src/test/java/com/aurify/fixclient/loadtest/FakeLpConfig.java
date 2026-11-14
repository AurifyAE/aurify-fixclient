package com.aurify.fixclient.loadtest;

import java.math.BigDecimal;
import java.nio.file.Path;

/**
 * Settings for one fake LP process, read from {@code -Dfakelp.*} system
 * properties so a load run can be reproduced from its command line alone.
 *
 * The outcome mix is in whole percent and must add up to 100. A profile that
 * cannot express an outcome (FXCubic has no partial fill or cancel) maps it to
 * the nearest one it can: see {@link FakeLpOutcomes}.
 */
public record FakeLpConfig(
        int port,
        Profile profile,
        long ackDelayMs,
        long fillDelayMs,
        long jitterMs,
        int fillPct,
        int partialPct,
        int noFillPct,
        int rejectPct,
        double partialRatio,
        BigDecimal referencePrice,
        BigDecimal priceStep,
        long seed,
        int maxOrdersPerSecond,
        String username,
        String password,
        Path reportFile
) {

    /** The provider a fake LP impersonates: the FIX version, dictionary and statuses it speaks. */
    public enum Profile {
        /** FIX 4.3. Statuses the gateway maps for FXCubic: PendingNew, Filled, Rejected. */
        FXCUBIC("FIX.4.3", "FIX43-fxcubic.xml", false),
        /** FIX 4.4. Also PartiallyFilled and Canceled, which is how an IOC remainder closes. */
        FINALTO("FIX.4.4", "FIX44-finalto.xml", true);

        final String beginString;
        final String dataDictionary;
        final boolean partialFills;

        Profile(String beginString, String dataDictionary, boolean partialFills) {
            this.beginString = beginString;
            this.dataDictionary = dataDictionary;
            this.partialFills = partialFills;
        }
    }

    public FakeLpConfig {
        if (fillPct < 0 || partialPct < 0 || noFillPct < 0 || rejectPct < 0
                || fillPct + partialPct + noFillPct + rejectPct != 100) {
            throw new IllegalArgumentException("fakelp outcome percentages must be >= 0 and add up to 100, got fill="
                    + fillPct + " partial=" + partialPct + " noFill=" + noFillPct + " reject=" + rejectPct);
        }
        if (partialRatio <= 0 || partialRatio >= 1) {
            throw new IllegalArgumentException("fakelp.partialRatio must be between 0 and 1, got " + partialRatio);
        }
        if (referencePrice.signum() <= 0) {
            throw new IllegalArgumentException("fakelp.referencePrice must be greater than zero");
        }
    }

    /** Every setting from {@code -Dfakelp.<name>}, with defaults suited to a first load run. */
    public static FakeLpConfig fromSystemProperties() {
        String report = System.getProperty("fakelp.reportFile", "");
        return new FakeLpConfig(
                Integer.getInteger("fakelp.port", 9878),
                Profile.valueOf(System.getProperty("fakelp.profile", "fxcubic").trim().toUpperCase()),
                Long.getLong("fakelp.ackDelayMs", 20L),
                Long.getLong("fakelp.fillDelayMs", 60L),
                Long.getLong("fakelp.jitterMs", 20L),
                Integer.getInteger("fakelp.fillPct", 85),
                Integer.getInteger("fakelp.partialPct", 10),
                Integer.getInteger("fakelp.noFillPct", 3),
                Integer.getInteger("fakelp.rejectPct", 2),
                Double.parseDouble(System.getProperty("fakelp.partialRatio", "0.6")),
                new BigDecimal(System.getProperty("fakelp.referencePrice", "2345.00")),
                new BigDecimal(System.getProperty("fakelp.priceStep", "0.25")),
                Long.getLong("fakelp.seed", 42L),
                Integer.getInteger("fakelp.maxOrdersPerSecond", 0),
                blankToNull(System.getProperty("fakelp.username")),
                blankToNull(System.getProperty("fakelp.password")),
                report.isBlank() ? null : Path.of(report));
    }

    /** Same settings with a different outcome mix: lets a test force one outcome. */
    public FakeLpConfig withMix(int fill, int partial, int noFill, int reject) {
        return new FakeLpConfig(port, profile, ackDelayMs, fillDelayMs, jitterMs, fill, partial, noFill, reject,
                partialRatio, referencePrice, priceStep, seed, maxOrdersPerSecond, username, password, reportFile);
    }

    public FakeLpConfig withPort(int newPort) {
        return new FakeLpConfig(newPort, profile, ackDelayMs, fillDelayMs, jitterMs, fillPct, partialPct, noFillPct,
                rejectPct, partialRatio, referencePrice, priceStep, seed, maxOrdersPerSecond, username, password,
                reportFile);
    }

    public FakeLpConfig withProfile(Profile newProfile) {
        return new FakeLpConfig(port, newProfile, ackDelayMs, fillDelayMs, jitterMs, fillPct, partialPct, noFillPct,
                rejectPct, partialRatio, referencePrice, priceStep, seed, maxOrdersPerSecond, username, password,
                reportFile);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
