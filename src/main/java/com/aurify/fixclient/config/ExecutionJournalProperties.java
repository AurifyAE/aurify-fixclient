package com.aurify.fixclient.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds on the gateway's replay buffer for execution reports.
 *
 * Deliberately small: the caller owns the durable execution ledger, and the
 * journal exists only to cover the window between a report arriving and the
 * caller recording it - a subscriber reconnecting, or an order the caller wants
 * to backfill. Sizing it like a database would quietly move business state back
 * into the gateway, which is what this design keeps out.
 */
@Data
@ConfigurationProperties(prefix = "fix-gateway.execution-journal")
public class ExecutionJournalProperties {

    /** Reports older than this are dropped, whatever the buffer still has room for. */
    private int retentionMinutes = 120;

    /** Hard cap on buffered reports, so a busy venue cannot exhaust the heap. */
    private int maxReports = 20_000;

    /** Cap per order, so one runaway order cannot evict every other order's history. */
    private int maxReportsPerOrder = 200;

    /** How often expired reports are swept out. */
    private long sweepIntervalMs = 60_000L;
}
