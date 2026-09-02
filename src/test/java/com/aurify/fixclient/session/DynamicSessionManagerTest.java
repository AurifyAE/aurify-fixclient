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
                Set.of(), 0L, 0D, 0D, "fp-other", "LP-ACC-2");

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
                Set.of(), 0L, 0D, 0D, "fp-down", "LP-ACC-1");

        LpSessionException e = assertThrows(LpSessionException.class,
                () -> manager.ensureSession(unreachable, SessionRole.TRADING));

        assertEquals("LP_SESSION_LOGON_TIMEOUT", e.getCode());
        assertTrue(registry.find("acct-down", SessionRole.TRADING).isEmpty(),
                "a failed session must be torn down, not left behind");
    }

    // --- fixtures -----------------------------------------------------------

    private LpSessionSpec spec(String fingerprint) {
        return new LpSessionSpec(
                "acct-1", "fxcubic", "FIX.4.3",
                new LpSessionSpec.FixSessionSpec(
                        "127.0.0.1", port, "AURIFY_TR", "LP_TR",
                        "aurify", "s3cret", false, true, 30, "", "", ""),
                Set.of("XAUUSD_1GRAM"), 500L, 1000D, 5000D, fingerprint, "LP-ACC-1");
    }

    /** Stands in for the LP: accepts the connection and answers the Logon. */
    private void startAcceptor() throws ConfigError {
        SessionSettings settings = new SessionSettings();
        settings.setString("ConnectionType", "acceptor");
        settings.setBool("UseDataDictionary", true);

        SessionID sessionId = new SessionID("FIX.4.3", "LP_TR", "AURIFY_TR");
        settings.setLong(sessionId, "SocketAcceptPort", port);
        settings.setString(sessionId, "StartTime", "00:00:00");
        settings.setString(sessionId, "EndTime", "23:59:59");
        settings.setString(sessionId, "DataDictionary", "FIX43.xml");
        settings.setBool(sessionId, "ResetOnLogon", true);

        acceptor = new SocketAcceptor(
                new NoOpApplication(acceptorLogons::incrementAndGet),
                new MemoryStoreFactory(), settings, new DefaultMessageFactory());
        acceptor.start();
    }

    private void startInitiatorSide() throws ConfigError {
        SessionLifecycleProperties lifecycle = new SessionLifecycleProperties();
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
