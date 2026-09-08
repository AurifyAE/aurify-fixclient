package com.aurify.fixclient.grpc;

import com.aurify.fixclient.canonical.enums.CanonicalOrdStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What {@code isTerminal} answers decides whether a hedge call can return.
 *
 * A status the provider's mapper leaves UNKNOWN is not terminal, so the RPC
 * waits out its whole deadline and the caller sees
 * FIX_EXECUTION_REPORT_TIMEOUT even though the venue answered and the hedge is
 * real. That is why the mapping of CANCELLED in particular matters: with
 * TimeInForce=IOC the normal close of a partially filled order is a cancel of
 * the remainder, not a fill.
 */
class PendingOrderRegistryTerminalityTest {

    @Test
    void anOutcomeCompletesTheWaitingCall() {
        assertTrue(PendingOrderRegistry.isTerminal(CanonicalOrdStatus.FILLED));
        assertTrue(PendingOrderRegistry.isTerminal(CanonicalOrdStatus.REJECTED));
        assertTrue(PendingOrderRegistry.isTerminal(CanonicalOrdStatus.CANCELLED));
    }

    @Test
    void anAcknowledgementDoesNot() {
        assertFalse(PendingOrderRegistry.isTerminal(CanonicalOrdStatus.NEW));
        assertFalse(PendingOrderRegistry.isTerminal(CanonicalOrdStatus.PENDING_NEW));
    }

    @Test
    void aPartialFillDoesNotEither() {
        // Quantity is still working at the venue; the call waits for the report
        // that closes the order (a fill or, for IOC, a cancel).
        assertFalse(PendingOrderRegistry.isTerminal(CanonicalOrdStatus.PARTIALLY_FILLED));
    }

    @Test
    void anUnmappedStatusIsNeverTreatedAsAnOutcome() {
        assertFalse(PendingOrderRegistry.isTerminal(CanonicalOrdStatus.UNKNOWN));
    }
}
