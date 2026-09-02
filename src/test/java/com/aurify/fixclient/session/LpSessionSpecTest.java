package com.aurify.fixclient.session;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LpSessionSpecTest {

    private static LpSessionSpec.FixSessionSpec session() {
        return new LpSessionSpec.FixSessionSpec(
                "tr.lp.example.com", 9002, "AURIFY_TR", "LP_TR",
                "aurify", "s3cret", true, false, 30, "", "", "");
    }

    private static LpSessionSpec spec(Set<String> allowedSymbols) {
        return new LpSessionSpec("acct-1", "FXCubic", "FIX.4.3", session(),
                allowedSymbols, 500L, 1000D, 5000D, "fp-1", "LP-ACC-1");
    }

    @Test
    void toStringNeverLeaksThePassword() {
        String rendered = spec(Set.of("XAUUSD_1GRAM")).toString();
        assertFalse(rendered.contains("s3cret"), "password must not appear in logs");
        assertTrue(rendered.contains("***"));
    }

    @Test
    void sessionToStringNeverLeaksThePassword() {
        assertFalse(session().toString().contains("s3cret"));
    }

    @Test
    void blankPasswordRendersAsNoneRatherThanMasked() {
        LpSessionSpec.FixSessionSpec noAuth = new LpSessionSpec.FixSessionSpec(
                "h", 1, "A", "B", "", "", false, false, 30, "", "", "");
        assertTrue(noAuth.toString().contains("<none>"));
    }

    @Test
    void providerIsNormalisedSoAdapterLookupIsCaseInsensitive() {
        assertEquals("fxcubic", spec(Set.of()).provider());
    }

    @Test
    void defaultsAreAppliedForOmittedSessionWindowAndHeartbeat() {
        LpSessionSpec.FixSessionSpec defaults = new LpSessionSpec.FixSessionSpec(
                "h", 1, "A", "B", "u", "p", false, false, 0, null, null, null);
        assertEquals(30, defaults.heartbeatIntervalSeconds());
        assertEquals("00:00:00", defaults.startTime());
        assertEquals("23:59:59", defaults.endTime());
    }

    @Test
    void requiredConnectionFieldsAreRejectedWhenMissing() {
        assertThrows(IllegalArgumentException.class, () -> new LpSessionSpec.FixSessionSpec(
                "", 1, "A", "B", "u", "p", false, false, 30, "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new LpSessionSpec.FixSessionSpec(
                "h", 0, "A", "B", "u", "p", false, false, 30, "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new LpSessionSpec.FixSessionSpec(
                "h", 1, "", "B", "u", "p", false, false, 30, "", "", ""));
    }

    @Test
    void tradingSessionIsMandatory() {
        assertThrows(NullPointerException.class, () -> new LpSessionSpec(
                "acct-1", "fxcubic", "FIX.4.3", null, Set.of(), 0L, 0D, 0D, "fp", "LP-ACC-1"));
    }

    @Test
    void theLpSideAccountIsCarriedSeparatelyFromOurOwnId() {
        LpSessionSpec spec = spec(Set.of());

        // Tag 1 must be the account at the venue, never our internal key.
        assertEquals("LP-ACC-1", spec.accountOrNull());
        assertNotEquals(spec.lpAccountId(), spec.accountOrNull());
    }

    @Test
    void anUnsetLpAccountYieldsNullSoTagOneIsOmittedRatherThanWrong() {
        LpSessionSpec noAccount = new LpSessionSpec("acct-1", "fxcubic", "FIX.4.3", session(),
                Set.of(), 0L, 0D, 0D, "fp-1", "  ");

        assertNull(noAccount.accountOrNull());
    }

    @Test
    void policyCarriesLimitsButNotCredentials() {
        var policy = spec(Set.of("XAUUSD_1GRAM")).policy();
        assertEquals(500L, policy.maxOrderSize());
        assertTrue(policy.allowsSymbol("XAUUSD_1GRAM"));
        assertFalse(policy.allowsSymbol("EURUSD"));
        assertFalse(policy.toString().contains("s3cret"));
    }
}
