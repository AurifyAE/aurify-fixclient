package com.aurify.fixclient.session;

import com.aurify.fixclient.config.QuickFixSessionConfigFactory;
import com.aurify.fixclient.config.SessionLifecycleProperties;
import com.aurify.fixclient.config.SessionMetadata;
import com.aurify.fixclient.config.SessionMetadataRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import quickfix.ConfigError;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.mina.initiator.IoSessionInitiator;

import java.lang.reflect.Method;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates FIX sessions on demand from a caller-supplied {@link LpSessionSpec},
 * instead of from a static application.yml tree read once at boot.
 *
 * Contract:
 *  - the first order for an LP account pays the logon cost; later orders reuse
 *    the live session
 *  - a changed spec fingerprint tears the session down and rebuilds it, so
 *    editing LP credentials upstream needs no gateway restart
 *  - work is serialised per LP account, so concurrent orders produce one logon
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicSessionManager {

    private static final long LOGON_POLL_INTERVAL_MS = 100L;
    private static final long LOGOUT_WAIT_MS = 1_000L;

    private final SocketInitiator socketInitiator;
    private final SessionSettings sessionSettings;
    private final QuickFixSessionConfigFactory sessionConfigFactory;
    private final SessionMetadataRegistry sessionMetadataRegistry;
    private final LpSessionRegistry lpSessionRegistry;
    private final SessionLifecycleProperties lifecycleProperties;

    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * Returns a session that is logged on and safe to send orders through,
     * creating and logging one on if needed. Blocks up to the configured logon
     * timeout on the slow path; returns immediately on the fast path.
     */
    public LpSessionEntry ensureSession(LpSessionSpec spec, SessionRole role) {
        synchronized (lockFor(spec.lpAccountId(), role)) {
            LpSessionEntry existing = lpSessionRegistry.find(spec.lpAccountId(), role).orElse(null);

            if (existing != null) {
                if (!existing.specFingerprint().equals(spec.specFingerprint())) {
                    log.info("Spec changed for LP account {} [{}] - rebuilding session",
                            spec.lpAccountId(), role);
                    teardown(existing);
                    existing = null;
                } else if (isLoggedOn(existing.sessionId())) {
                    existing.markLoggedOn();
                    return existing;
                }
            }

            LpSessionEntry entry = existing != null ? existing : create(spec, role);
            awaitLogon(entry);
            return entry;
        }
    }

    /** Current view of a session without creating or changing anything. */
    public LpSessionEntry statusOf(String lpAccountId, SessionRole role) {
        return lpSessionRegistry.find(lpAccountId, role).orElse(null);
    }

    /** Logs out and removes a session. The next ensureSession recreates it. */
    public boolean closeSession(String lpAccountId, SessionRole role) {
        synchronized (lockFor(lpAccountId, role)) {
            LpSessionEntry entry = lpSessionRegistry.find(lpAccountId, role).orElse(null);
            if (entry == null) {
                return false;
            }
            teardown(entry);
            return true;
        }
    }

    private Object lockFor(String lpAccountId, SessionRole role) {
        return locks.computeIfAbsent(lpAccountId + "#" + role.name(), k -> new Object());
    }

    private LpSessionEntry create(LpSessionSpec spec, SessionRole role) {
        SessionID sessionId = sessionConfigFactory.sessionIdFor(spec, role);

        // An LP keys its side by comp IDs and usually allows one session at a
        // time, so a second account using the same ones would just get its
        // connection dropped - an 8 second timeout that says nothing useful.
        lpSessionRegistry.findConflicting(sessionId, spec.lpAccountId()).ifPresent(other -> {
            throw new LpSessionException("LP_SESSION_COMP_ID_CONFLICT",
                    "LP account " + other.lpAccountId() + " already holds a session as "
                            + sessionId.getSenderCompID() + "->" + sessionId.getTargetCompID()
                            + ". An LP allows one session per comp ID pair: give each LP account "
                            + "its own credentials, or close the other session first.");
        });

        // Settings must land in the same SessionSettings instance the initiator
        // was constructed with - createDynamicSession reads back out of it.
        sessionConfigFactory.applySpec(sessionSettings, sessionId, spec, role);

        LpSessionSpec.FixSessionSpec fixSession = QuickFixSessionConfigFactory.sessionOf(spec, role);
        sessionMetadataRegistry.put(sessionId, new SessionMetadata(
                spec.provider(), role, fixSession.username(), fixSession.password(), spec.lpAccountId()));

        LpSessionEntry entry = new LpSessionEntry(
                spec.lpAccountId(), role, sessionId, spec.provider(), spec.specFingerprint());
        entry.markConnecting();
        lpSessionRegistry.put(entry);

        try {
            socketInitiator.createDynamicSession(sessionId);
            log.info("Created dynamic FIX session {} for LP account {} -> {}",
                    sessionId, spec.lpAccountId(), fixSession);
        } catch (ConfigError e) {
            entry.markFailed();
            lpSessionRegistry.remove(spec.lpAccountId(), role);
            throw LpSessionException.createFailed(spec.lpAccountId(), e);
        }
        return entry;
    }

    private void awaitLogon(LpSessionEntry entry) {
        long timeoutSeconds = lifecycleProperties.getLogonTimeoutSeconds();
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

        while (System.currentTimeMillis() < deadline) {
            if (isLoggedOn(entry.sessionId())) {
                entry.markLoggedOn();
                return;
            }
            try {
                Thread.sleep(LOGON_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                entry.markFailed();
                throw new LpSessionException("LP_SESSION_INTERRUPTED",
                        "Interrupted while waiting for logon to " + entry.lpAccountId());
            }
        }

        entry.markFailed();
        teardown(entry);
        throw LpSessionException.logonTimeout(entry.lpAccountId(), timeoutSeconds);
    }

    private boolean isLoggedOn(SessionID sessionId) {
        Session session = Session.lookupSession(sessionId);
        return session != null && session.isLoggedOn();
    }

    /**
     * Fully dismantles a session so the same SessionID can be created again.
     *
     * QuickFIX's {@code removeDynamicSession} only drops the connector's map
     * entry: the Session stays in QuickFIX's global registry and its reconnect
     * task keeps running. Left that way, a later {@code createDynamicSession}
     * for the same ID never logs on, and the stale task keeps dialling the LP.
     * All three have to go.
     */
    void teardown(LpSessionEntry entry) {
        SessionID sessionId = entry.sessionId();
        Session session = Session.lookupSession(sessionId);

        try {
            if (session != null && session.isLoggedOn()) {
                session.logout("Session closed by gateway");
                awaitLogout(session);
            }
        } catch (Exception e) {
            log.warn("Logout failed for {} - removing anyway", sessionId, e);
        }

        stopReconnectTask(sessionId);

        try {
            socketInitiator.removeDynamicSession(sessionId);
        } catch (Exception e) {
            log.warn("removeDynamicSession failed for {}", sessionId, e);
        }

        try {
            if (session != null) {
                session.close(); // unregisters it from QuickFIX's global session map
            }
        } catch (Exception e) {
            log.warn("Closing session {} failed", sessionId, e);
        }

        lpSessionRegistry.remove(entry.lpAccountId(), entry.role());
        log.info("Removed FIX session {} for LP account {}", sessionId, entry.lpAccountId());
    }

    /** Gives the LOGOUT a moment to reach the wire before the socket is dropped. */
    private void awaitLogout(Session session) {
        long deadline = System.currentTimeMillis() + LOGOUT_WAIT_MS;
        while (session.isLoggedOn() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(LOGON_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Stops the reconnect task bound to this SessionID.
     *
     * {@code IoSessionInitiator.stop()} is package-private in QuickFIX/J and
     * there is no public equivalent short of stopping the whole initiator,
     * which would drop every other LP's session too.
     */
    private void stopReconnectTask(SessionID sessionId) {
        try {
            for (IoSessionInitiator sessionInitiator : socketInitiator.getInitiators()) {
                if (!sessionId.equals(sessionInitiator.getSessionID())) {
                    continue;
                }
                Method stop = IoSessionInitiator.class.getDeclaredMethod("stop");
                stop.setAccessible(true);
                stop.invoke(sessionInitiator);
            }
        } catch (Exception e) {
            log.warn("Could not stop the reconnect task for {} - it may keep dialling the LP",
                    sessionId, e);
        }
    }
}
