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
import quickfix.mina.ssl.SSLSupport;

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
                Set.of(), 0L, 0D, 0D, "fp", "", "", null, "");
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
    void sslFollowsWhatTheCallerAskedFor() throws Exception {
        SessionSettings withSsl = applied(spec(true));
        assertEquals("Y", withSsl.getString(idOf(spec(true)), SSLSupport.SETTING_USE_SSL));

        SessionSettings withoutSsl = applied(spec(false));
        assertEquals("N", withoutSsl.getString(idOf(spec(false)), SSLSupport.SETTING_USE_SSL));
    }

    /**
     * SessionSettings is a shared bean that outlives any one spec, so writing
     * the SSL flag only when it is true leaves the previous "Y" in place and
     * SSL can be switched on for an LP but never back off. It cost a live
     * debugging round: an FXCubic pricing session kept failing its TLS
     * handshake against a plaintext port while the stored config said
     * useSsl=false, and nothing in the gateway's config surface disagreed.
     */
    @Test
    void turningSslOffActuallyTurnsItOffOnASessionThatPreviouslyHadIt() throws Exception {
        SessionSettings settings = factory.baseSettings();
        SessionID sessionId = idOf(spec(true));

        factory.applySpec(settings, sessionId, spec(true), SessionRole.TRADING);
        assertEquals("Y", settings.getString(sessionId, SSLSupport.SETTING_USE_SSL));

        // Same SessionID, same settings object - exactly what a re-ensure does.
        factory.applySpec(settings, sessionId, spec(false), SessionRole.TRADING);

        assertEquals("N", settings.getString(sessionId, SSLSupport.SETTING_USE_SSL),
                "SSL must be switchable off, not just on");
        assertEquals("N", settings.getString(sessionId, SSLSupport.SETTING_USE_SNI),
                "SNI must not outlive the SSL setting that justified it");
    }

    /**
     * Every existing venue has no SenderSubID. Their SessionID must stay
     * exactly what it was, because the file store - and with it the sequence
     * numbers - is keyed on it.
     */
    @Test
    void withoutASenderSubIdTheSessionIdIsUnchanged() {
        assertEquals(new SessionID("FIX.4.4", "SENDER", "TARGET", "acct-1-TRADING"), idOf(spec(false)));
    }

    private LpSessionSpec stonexSpec() {
        return new LpSessionSpec(
                "acct-stonex", "stonex", "FIX.4.3",
                new LpSessionSpec.FixSessionSpec(
                        "fx.example.com", 13976, "order.APITESTINTM.082", "demo.fxgrid",
                        "API82", "secret", false, false, 30, "", "", "", "APITESTINTM"),
                Set.of("XAUUSD"), 0L, 0D, 0D, "fp", "", "", null, "", "BRKD");
    }

    @Test
    void aSenderSubIdBecomesPartOfTheSessionId() {
        SessionID sessionId = idOf(stonexSpec());

        assertEquals("APITESTINTM", sessionId.getSenderSubID());
        assertEquals("order.APITESTINTM.082", sessionId.getSenderCompID());
        assertEquals("demo.fxgrid", sessionId.getTargetCompID());
    }

    /** The point of carrying it in the SessionID: QuickFIX/J itself puts tag
     *  50 on the Logon (and every later message), which StoneX requires. */
    @Test
    void quickFixStampsSenderSubIdOnTheLogon() throws Exception {
        LpSessionSpec spec = stonexSpec();
        SessionSettings settings = applied(spec);
        SessionID sessionId = idOf(spec);
        List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();

        Session session = new quickfix.DefaultSessionFactory(new quickfix.ApplicationAdapter(),
                new quickfix.MemoryStoreFactory(), new quickfix.SLF4JLogFactory(new SessionSettings()))
                .create(sessionId, settings);
        try {
            session.setResponder(new quickfix.Responder() {
                @Override public boolean send(String data) { return sent.add(data); }
                @Override public void disconnect() { }
                @Override public String getRemoteAddress() { return "test"; }
            });
            session.next();

            assertEquals(1, sent.size(), "expected exactly the Logon, got " + sent);
            String logon = sent.get(0);
            assertTrue(logon.contains("\u000135=A\u0001"), logon);
            assertTrue(logon.contains("\u000150=APITESTINTM\u0001"), "SenderSubID missing from Logon: " + logon);
        } finally {
            session.close();
        }
    }
}
