package com.aurify.fixclient.config;

import com.aurify.fixclient.provider.LiquidityProviderAdapter;
import com.aurify.fixclient.provider.ProviderAdapterRegistry;
import com.aurify.fixclient.session.LpSessionRegistry;
import com.aurify.fixclient.session.LpSessionSpec;
import com.aurify.fixclient.session.SessionRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class QuickFixSessionConfigFactoryTest {

    private SessionLifecycleProperties lifecycleProperties;
    private QuickFixSessionConfigFactory factory;

    @BeforeEach
    void setUp() {
        lifecycleProperties = new SessionLifecycleProperties();
        List<LiquidityProviderAdapter> adapters = List.of();
        factory = new QuickFixSessionConfigFactory(
                lifecycleProperties, new ProviderAdapterRegistry(adapters, new LpSessionRegistry()));
    }

    private LpSessionSpec spec(boolean useSsl) {
        return new LpSessionSpec(
                "acct-1", "finalto", "FIX.4.4",
                new LpSessionSpec.FixSessionSpec(
                        "lp.example.com", 34550, "SENDER", "TARGET",
                        "u", "p", useSsl, true, 30, "", "", ""),
                Set.of(), 0L, 0D, 0D, "fp", "", "");
    }

    private SessionSettings applied(LpSessionSpec spec) {
        SessionSettings settings = factory.baseSettings();
        SessionID sessionId = factory.sessionIdFor(spec, SessionRole.TRADING);
        factory.applySpec(settings, sessionId, spec, SessionRole.TRADING);
        return settings;
    }

    private SessionID idOf(LpSessionSpec spec) {
        return factory.sessionIdFor(spec, SessionRole.TRADING);
    }

    /**
     * QuickFIX keeps its own logon timer, default 10s, and disconnects with
     * "Timed out waiting for logon response" when it expires. Unset, it caps
     * every logon at 10s no matter how long awaitLogon is willing to wait -
     * which is invisible in this gateway's own configuration and reads in the
     * FIX log like the LP hung up. This is the regression guard for that.
     */
    @Test
    void quickFixOwnLogonTimeoutIsWrittenSoItCannotSilentlyCapTheWait() throws Exception {
        lifecycleProperties.setLogonTimeoutSeconds(25);
        LpSessionSpec spec = spec(false);

        SessionSettings settings = applied(spec);

        assertEquals(24L, settings.getLong(idOf(spec), Session.SETTING_LOGON_TIMEOUT),
                "QuickFIX's logon timer must sit just inside awaitLogon's wait");
    }

    @Test
    void theQuickFixTimerStaysInsideOurWaitSoAwaitLogonIsWhatReportsASlowLp() throws Exception {
        lifecycleProperties.setLogonTimeoutSeconds(30);
        LpSessionSpec spec = spec(false);

        long quickFixTimeout = applied(spec).getLong(idOf(spec), Session.SETTING_LOGON_TIMEOUT);

        assertTrue(quickFixTimeout < lifecycleProperties.getLogonTimeoutSeconds(),
                "QuickFIX must give up before awaitLogon does, so the error names the real cause");
    }

    @Test
    void aOneSecondWaitDoesNotProduceAZeroOrNegativeTimeout() throws Exception {
        lifecycleProperties.setLogonTimeoutSeconds(1);
        LpSessionSpec spec = spec(false);

        assertEquals(1L, applied(spec).getLong(idOf(spec), Session.SETTING_LOGON_TIMEOUT));
    }

    @Test
    void sslIsEnabledOnlyWhenTheCallerAsksForIt() throws Exception {
        SessionSettings withSsl = applied(spec(true));
        assertEquals("Y", withSsl.getString(idOf(spec(true)), quickfix.mina.ssl.SSLSupport.SETTING_USE_SSL));

        SessionSettings withoutSsl = applied(spec(false));
        assertFalse(withoutSsl.isSetting(idOf(spec(false)), quickfix.mina.ssl.SSLSupport.SETTING_USE_SSL));
    }
}
