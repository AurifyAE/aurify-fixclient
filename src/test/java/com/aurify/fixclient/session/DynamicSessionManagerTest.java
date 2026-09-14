package com.aurify.fixclient.session;

import com.aurify.fixclient.config.QuickFixSessionConfigFactory;
import com.aurify.fixclient.config.SessionLifecycleProperties;
import com.aurify.fixclient.config.SessionMetadataRegistry;
import com.aurify.fixclient.provider.ProviderAdapterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quickfix.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the real DynamicSessionManager against a real QuickFIX/J acceptor
 * running in this JVM, so the logon, reuse and rebuild paths are exercised
 * end to end without needing a liquidity provider.
 */
class DynamicSessionManagerTest {

    private int port;
    private SocketAcceptor acceptor;
    private int pricingPort;
    private SocketAcceptor pricingAcceptor;
    private SessionLifecycleProperties lifecycle;
    private SocketInitiator initiator;
    private SessionSettings initiatorSettings;
    private DynamicSessionManager manager;
    private LpSessionRegistry registry;
    private final AtomicInteger acceptorLogons = new AtomicInteger();

    @BeforeEach
    void startAcceptorAndManager() throws Exception {
        port = freePort();
        startAcceptor();
        startInitiatorSide();
    }

    @AfterEach
    void stop() {
        if (initiator != null) initiator.stop(true);
        if (acceptor != null) acceptor.stop(true);
        if (pricingAcceptor != null) pricingAcceptor.stop(true);
    }

    @Test
    void firstCallLogsOnAndReturnsALiveSession() {
        LpSessionEntry entry = manager.ensureSession(spec("fp-1"), SessionRole.TRADING);

        assertEquals(SessionState.LOGGED_ON, entry.state());
        assertEquals("acct-1", entry.lpAccountId());
        assertTrue(Session.lookupSession(entry.sessionId()).isLoggedOn());
        assertEquals(1, acceptorLogons.get());
    }

    @Test
    void sessionIdIsKeyedByLpAccountSoOneProviderCanHaveMany() {
        LpSessionEntry entry = manager.ensureSession(spec("fp-1"), SessionRole.TRADING);

        assertEquals("acct-1-TRADING", entry.sessionId().getSessionQualifier());
    }

    @Test
    void secondCallWithTheSameSpecReusesTheSessionWithoutLoggingOnAgain() {
        LpSessionEntry first = manager.ensureSession(spec("fp-1"), SessionRole.TRADING);
        LpSessionEntry second = manager.ensureSession(spec("fp-1"), SessionRole.TRADING);

        assertSame(first, second);
        assertEquals(1, acceptorLogons.get(), "an unchanged spec must not cause a second logon");
    }

    @Test
    void reuseIsFastBecauseItSkipsTheLogonWait() {
        manager.ensureSession(spec("fp-1"), SessionRole.TRADING);

        long start = System.currentTimeMillis();
        manager.ensureSession(spec("fp-1"), SessionRole.TRADING);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 500, "reuse took " + elapsed + "ms; it should not wait for a logon");
    }

    @Test
    void aChangedFingerprintRebuildsTheSessionWithoutARestart() {
        LpSessionEntry before = manager.ensureSession(spec("fp-1"), SessionRole.TRADING);
        LpSessionEntry after = manager.ensureSession(spec("fp-2-credentials-rotated"), SessionRole.TRADING);

        assertNotSame(before, after);
        assertEquals("fp-2-credentials-rotated", after.specFingerprint());
        assertEquals(SessionState.LOGGED_ON, after.state());
        assertEquals(2, acceptorLogons.get(), "a changed spec must force a fresh logon");
    }

    @Test
    void pricingAndTradingSessionsAreIndependentAndKeyedByRole() throws Exception {
        startPricingAcceptor();
        LpSessionEntry trading = manager.ensureSession(specWithPricing("fp-tr-1", "fp-px-1"), SessionRole.TRADING);
        LpSessionEntry pricing = manager.ensureSession(specWithPricing("fp-tr-1", "fp-px-1"), SessionRole.PRICING);

        assertEquals(SessionState.LOGGED_ON, trading.state());
        assertEquals(SessionState.LOGGED_ON, pricing.state());
        assertEquals("acct-1-TRADING", trading.sessionId().getSessionQualifier());
        assertEquals("acct-1-PRICING", pricing.sessionId().getSessionQualifier());
        assertEquals(2, acceptorLogons.get(), "trading and pricing are two independent logons");
    }

    /**
     * The whole reason pricingFingerprint is a field separate from
     * specFingerprint: adding or editing a pricing session must never force a
     * live trading session to relogon, and vice versa - a trading credential
     * rotation must not silently drop a pricing subscription either.
     */
    @Test
    void aPricingOnlyFingerprintChangeRebuildsOnlyThePricingSession() throws Exception {
        startPricingAcceptor();
        manager.ensureSession(specWithPricing("fp-tr-1", "fp-px-1"), SessionRole.TRADING);
        LpSessionEntry pricingFirst = manager.ensureSession(specWithPricing("fp-tr-1", "fp-px-1"), SessionRole.PRICING);
        assertEquals(2, acceptorLogons.get());

        // Trading fingerprint unchanged, pricing fingerprint rotated (e.g. the
        // pricing password was edited in the CRM).
        manager.ensureSession(specWithPricing("fp-tr-1", "fp-px-2"), SessionRole.TRADING);
        assertEquals(2, acceptorLogons.get(),
                "a pricing-only fingerprint change must not relogon the trading session");

        LpSessionEntry pricingRebuilt =
                manager.ensureSession(specWithPricing("fp-tr-1", "fp-px-2"), SessionRole.PRICING);
        assertEquals(3, acceptorLogons.get(),
                "a changed pricing fingerprint must force a fresh pricing logon");
        assertNotSame(pricingFirst, pricingRebuilt);
        assertEquals("fp-px-2", pricingRebuilt.specFingerprint());
    }

    @Test
    void aTradingOnlyFingerprintChangeRebuildsOnlyTheTradingSession() throws Exception {
        startPricingAcceptor();
        LpSessionEntry tradingFirst = manager.ensureSession(specWithPricing("fp-tr-1", "fp-px-1"), SessionRole.TRADING);
        manager.ensureSession(specWithPricing("fp-tr-1", "fp-px-1"), SessionRole.PRICING);
        assertEquals(2, acceptorLogons.get());

        // Pricing fingerprint unchanged, trading fingerprint rotated (e.g. the
        // trading password was edited in the CRM).
        LpSessionEntry tradingRebuilt =
                manager.ensureSession(specWithPricing("fp-tr-2", "fp-px-1"), SessionRole.TRADING);
        assertEquals(3, acceptorLogons.get(), "a changed trading fingerprint must force a fresh trading logon");
        assertNotSame(tradingFirst, tradingRebuilt);

        manager.ensureSession(specWithPricing("fp-tr-2", "fp-px-1"), SessionRole.PRICING);
        assertEquals(3, acceptorLogons.get(),
                "a trading-only fingerprint change must not relogon the pricing session");
    }

    @Test
    void concurrentCallersProduceExactlyOneLogon() throws Exception {
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch startGate = new CountDownLatch(1);
        CompletionService<LpSessionEntry> completions = new ExecutorCompletionService<>(pool);

        for (int i = 0; i < callers; i++) {
            completions.submit(() -> {
                startGate.await();
                return manager.ensureSession(spec("fp-1"), SessionRole.TRADING);
            });
        }
        startGate.countDown();

        LpSessionEntry firstResult = completions.take().get(20, TimeUnit.SECONDS);
        for (int i = 1; i < callers; i++) {
            assertSame(firstResult, completions.take().get(20, TimeUnit.SECONDS),
                    "every caller must get the same session");
        }
        pool.shutdownNow();

        assertEquals(1, acceptorLogons.get(), "per-account locking must collapse concurrent logons into one");
    }

    @Test
    void closingASessionRemovesItAndTheNextCallRebuildsIt() {
        manager.ensureSession(spec("fp-1"), SessionRole.TRADING);

        assertTrue(manager.closeSession("acct-1", SessionRole.TRADING));
        assertTrue(registry.find("acct-1", SessionRole.TRADING).isEmpty());
        assertNull(manager.statusOf("acct-1", SessionRole.TRADING));

        assertEquals(SessionState.LOGGED_ON,
                manager.ensureSession(spec("fp-1"), SessionRole.TRADING).state());
        assertEquals(2, acceptorLogons.get());
    }

    @Test
    void aSecondAccountReusingTheSameCompIdsIsRefusedImmediately() {
        manager.ensureSession(spec("fp-1"), SessionRole.TRADING);

        LpSessionSpec sameCompIds = new LpSessionSpec(
                "acct-2", "fxcubic", "FIX.4.3",
                new LpSessionSpec.FixSessionSpec(
                        "127.0.0.1", port, "AURIFY_TR", "LP_TR",
                        "aurify", "s3cret", false, true, 30, "", "", ""),
                Set.of(), 0L, 0D, 0D, "fp-other", "LP-ACC-2", "", null, "");

        long start = System.currentTimeMillis();
        LpSessionException e = assertThrows(LpSessionException.class,
                () -> manager.ensureSession(sameCompIds, SessionRole.TRADING));

        assertEquals("LP_SESSION_COMP_ID_CONFLICT", e.getCode());
        assertTrue(e.getMessage().contains("acct-1"), "the message should name the other account");
        assertTrue(System.currentTimeMillis() - start < 1000,
                "the clash must be reported at once, not after a logon timeout");
    }

    @Test
    void closingASessionThatDoesNotExistIsNotAnError() {
        assertFalse(manager.closeSession("never-seen", SessionRole.TRADING));
    }

    @Test
    void anUnreachableLpFailsWithAClearCodeAndLeavesNoHalfOpenSession() {
        LpSessionSpec unreachable = new LpSessionSpec(
                "acct-down", "fxcubic", "FIX.4.3",
                new LpSessionSpec.FixSessionSpec(
                        "127.0.0.1", freePort(), "AURIFY_TR", "LP_TR",
                        "u", "p", false, false, 30, "", "", ""),
                Set.of(), 0L, 0D, 0D, "fp-down", "LP-ACC-1", "", null, "");

        LpSessionException e = assertThrows(LpSessionException.class,
                () -> manager.ensureSession(unreachable, SessionRole.TRADING));

        assertEquals("LP_SESSION_LOGON_TIMEOUT", e.getCode());
        assertTrue(registry.find("acct-down", SessionRole.TRADING).isEmpty(),
                "a failed session must be torn down, not left behind");
    }

    /**
     * A venue that drops a connection without a FIX-level answer - Finalto's
     * demo does it to roughly every other reconnect - used to fail the whole
     * hedge, and upstream that rolls back the metal transaction. One rebuild
     * and retry recovers it.
     *
     * The LP here is absent for the first attempt and present for the second.
     */
    @Test
    void aLogonThatGetsNoAnswerIsRetriedOnAFreshSession() throws Exception {
        int latePort = freePort();
        lifecycle.setLogonTimeoutSeconds(10);   // 5s per attempt
        lifecycle.setLogonAttempts(2);

        // Comes up during attempt 1, whose dial has already failed, so it is
        // attempt 2's brand new session that finds it listening.
        SocketAcceptor lateAcceptor = buildAcceptor(latePort, "LP_TR", "AURIFY_TR");
        Thread starter = new Thread(() -> {
            try {
                Thread.sleep(3_000L);
                lateAcceptor.start();
            } catch (Exception ignored) {
                Thread.currentThread().interrupt();
            }
        });
        starter.start();

        try {
            LpSessionEntry entry = manager.ensureSession(specOn(latePort, "acct-late", "fp-late"), SessionRole.TRADING);
            assertEquals(SessionState.LOGGED_ON, entry.state(), "the retry should have logged on");
            assertEquals(1, acceptorLogons.get(), "only the successful attempt reaches the LP");
        } finally {
            starter.join(15_000L);
            lateAcceptor.stop(true);
        }
    }

    /**
     * The attempts divide {@code logonTimeoutSeconds}; they do not each get it.
     * Giving each attempt the full timeout would silently double the worst case
     * and push it past the caller's RPC deadline, which is the thing this
     * budget exists to stay inside.
     */
    @Test
    void retryingSharesTheLogonBudgetInsteadOfDoublingIt() {
        lifecycle.setLogonTimeoutSeconds(6);
        lifecycle.setLogonAttempts(2);

        long startedAt = System.currentTimeMillis();
        LpSessionException e = assertThrows(LpSessionException.class,
                () -> manager.ensureSession(specOn(freePort(), "acct-dead", "fp-dead"), SessionRole.TRADING));
        long elapsed = System.currentTimeMillis() - startedAt;

        assertEquals("LP_SESSION_LOGON_TIMEOUT", e.getCode());
        assertTrue(elapsed < 11_000L, "two attempts must share the 6s budget, took " + elapsed + "ms");
        assertTrue(registry.find("acct-dead", SessionRole.TRADING).isEmpty(),
                "every attempt must be torn down, not left behind");
    }

    @Test
    void oneAttemptRestoresTheOldSingleShotBehaviour() {
        lifecycle.setLogonTimeoutSeconds(4);
        lifecycle.setLogonAttempts(1);

        LpSessionException e = assertThrows(LpSessionException.class,
                () -> manager.ensureSession(specOn(freePort(), "acct-once", "fp-once"), SessionRole.TRADING));

        assertEquals("LP_SESSION_LOGON_TIMEOUT", e.getCode());
        assertTrue(registry.find("acct-once", SessionRole.TRADING).isEmpty());
    }

    // --- fixtures -----------------------------------------------------------

    private LpSessionSpec spec(String fingerprint) {
        return new LpSessionSpec(
                "acct-1", "fxcubic", "FIX.4.3",
                new LpSessionSpec.FixSessionSpec(
                        "127.0.0.1", port, "AURIFY_TR", "LP_TR",
                        "aurify", "s3cret", false, true, 30, "", "", ""),
                Set.of("XAUUSD_1GRAM"), 500L, 1000D, 5000D, fingerprint, "LP-ACC-1", "", null, "");
    }

    /** The same spec against an arbitrary port and account. */
    private LpSessionSpec specOn(int targetPort, String lpAccountId, String fingerprint) {
        return new LpSessionSpec(
                lpAccountId, "fxcubic", "FIX.4.3",
                new LpSessionSpec.FixSessionSpec(
                        "127.0.0.1", targetPort, "AURIFY_TR", "LP_TR",
                        "aurify", "s3cret", false, true, 30, "", "", ""),
                Set.of("XAUUSD_1GRAM"), 500L, 1000D, 5000D, fingerprint, "LP-ACC-1", "", null, "");
    }

    /**
     * Same trading session as {@link #spec}, plus a pricing session on a
     * second comp-ID pair against {@link #pricingAcceptor} - a real acceptor
     * only ever answers one live connection per (unqualified) SessionID, so
     * trading and pricing need separate comp IDs here even though the
     * gateway's own registry keys them by role and would tolerate sharing.
     */
    private LpSessionSpec specWithPricing(String tradingFingerprint, String pricingFingerprint) {
        return new LpSessionSpec(
                "acct-1", "fxcubic", "FIX.4.3",
                new LpSessionSpec.FixSessionSpec(
                        "127.0.0.1", port, "AURIFY_TR", "LP_TR",
                        "aurify", "s3cret", false, true, 30, "", "", ""),
                Set.of("XAUUSD_1GRAM"), 500L, 1000D, 5000D, tradingFingerprint, "LP-ACC-1", "",
                new LpSessionSpec.FixSessionSpec(
                        "127.0.0.1", pricingPort, "AURIFY_PX", "LP_PX",
                        "aurify", "px-s3cret", false, true, 30, "", "", ""),
                pricingFingerprint);
    }

    /** Starts {@link #pricingAcceptor} on a fresh port - only the pricing
     *  tests need it, so it is not part of the shared @BeforeEach setup. */
    private void startPricingAcceptor() throws Exception {
        pricingPort = freePort();
        pricingAcceptor = buildAcceptor(pricingPort, "LP_PX", "AURIFY_PX");
        pricingAcceptor.start();
    }

    /** Stands in for the LP: accepts the connection and answers the Logon. */
    private void startAcceptor() throws ConfigError {
        acceptor = buildAcceptor(port, "LP_TR", "AURIFY_TR");
        acceptor.start();
    }

    private SocketAcceptor buildAcceptor(int acceptPort, String senderCompId, String targetCompId) throws ConfigError {
        SessionSettings settings = new SessionSettings();
        settings.setString("ConnectionType", "acceptor");
        settings.setBool("UseDataDictionary", true);

        SessionID sessionId = new SessionID("FIX.4.3", senderCompId, targetCompId);
        settings.setLong(sessionId, "SocketAcceptPort", acceptPort);
        settings.setString(sessionId, "StartTime", "00:00:00");
        settings.setString(sessionId, "EndTime", "23:59:59");
        settings.setString(sessionId, "DataDictionary", "FIX43.xml");
        settings.setBool(sessionId, "ResetOnLogon", true);

        return new SocketAcceptor(
                new NoOpApplication(acceptorLogons::incrementAndGet),
                new MemoryStoreFactory(), settings, new DefaultMessageFactory());
    }

    private void startInitiatorSide() throws ConfigError {
        lifecycle = new SessionLifecycleProperties();
        lifecycle.setLogonTimeoutSeconds(8);
        lifecycle.setIdleTimeoutMinutes(0); // no reaping during the test

        registry = new LpSessionRegistry();
        QuickFixSessionConfigFactory configFactory =
                new QuickFixSessionConfigFactory(lifecycle, new ProviderAdapterRegistry(List.of(), registry));
        initiatorSettings = configFactory.baseSettings();

        initiator = new SocketInitiator(
                new NoOpApplication(() -> {}),
                new MemoryStoreFactory(), initiatorSettings, new DefaultMessageFactory());
        initiator.start();

        manager = new DynamicSessionManager(
                initiator, initiatorSettings, configFactory,
                new SessionMetadataRegistry(), registry, lifecycle);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Could not find a free port", e);
        }
    }

    /** QuickFIX handles Logon/Heartbeat itself; this only counts logons. */
    private record NoOpApplication(Runnable onLogonCallback) implements Application {
        @Override public void onCreate(SessionID sessionId) { }
        @Override public void onLogon(SessionID sessionId) { onLogonCallback.run(); }
        @Override public void onLogout(SessionID sessionId) { }
        @Override public void toAdmin(Message message, SessionID sessionId) { }
        @Override public void fromAdmin(Message message, SessionID sessionId) { }
        @Override public void toApp(Message message, SessionID sessionId) { }
        @Override public void fromApp(Message message, SessionID sessionId) { }
    }
}
