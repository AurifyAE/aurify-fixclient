package com.aurify.fixclient.journal;

import com.aurify.fixclient.canonical.event.CanonicalExecutionReport;

import java.time.Instant;

/**
 * One execution report as the gateway saw it, with the attribution the caller
 * needs to file it against the right LP account and the raw FIX behind it.
 *
 * {@code receivedAt} is the gateway's own clock, not the venue's: replay is
 * ordered by when we saw a report, so a venue with a skewed clock cannot cause
 * a subscriber to silently skip reports it has not seen yet.
 */
public record JournaledReport(
        String lpAccountId,
        CanonicalExecutionReport report,
        String rawFix,
        Instant receivedAt) {

    public String clOrdId() {
        return report.getClOrdId();
    }
}
