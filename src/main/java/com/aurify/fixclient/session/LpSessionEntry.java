package com.aurify.fixclient.session;

import quickfix.SessionID;

/**
 * Runtime bookkeeping for one live LP session. Mutable and shared across
 * threads, so every field that changes after construction is volatile.
 *
 * Deliberately holds no business state: everything here is rebuildable from
 * the next spec the caller sends.
 */
public class LpSessionEntry {

    private final String lpAccountId;
    private final SessionRole role;
    private final SessionID sessionId;
    private final String provider;

    private volatile String specFingerprint;
    private volatile SessionState state = SessionState.ABSENT;
    private volatile long loggedOnAtEpochMs;
    private volatile long lastUsedAtEpochMs;

    public LpSessionEntry(String lpAccountId, SessionRole role, SessionID sessionId,
                          String provider, String specFingerprint) {
        this.lpAccountId = lpAccountId;
        this.role = role;
        this.sessionId = sessionId;
        this.provider = provider;
        this.specFingerprint = specFingerprint;
        this.lastUsedAtEpochMs = System.currentTimeMillis();
    }

    public String lpAccountId() { return lpAccountId; }
    public SessionRole role() { return role; }
    public SessionID sessionId() { return sessionId; }
    public String provider() { return provider; }
    public String specFingerprint() { return specFingerprint; }
    public SessionState state() { return state; }
    public long loggedOnAtEpochMs() { return loggedOnAtEpochMs; }
    public long lastUsedAtEpochMs() { return lastUsedAtEpochMs; }

    public void setSpecFingerprint(String fingerprint) { this.specFingerprint = fingerprint; }

    public void markConnecting() {
        this.state = SessionState.CONNECTING;
        touch();
    }

    public void markLoggedOn() {
        this.state = SessionState.LOGGED_ON;
        this.loggedOnAtEpochMs = System.currentTimeMillis();
        touch();
    }

    public void markFailed() {
        this.state = SessionState.FAILED;
        this.loggedOnAtEpochMs = 0L;
    }

    /** Session dropped but the entry survives - QuickFIX may reconnect on its own. */
    public void markDisconnected() {
        if (state == SessionState.LOGGED_ON) {
            this.state = SessionState.CONNECTING;
        }
        this.loggedOnAtEpochMs = 0L;
    }

    /** Resets the idle clock. Called on every order that uses this session. */
    public void touch() {
        this.lastUsedAtEpochMs = System.currentTimeMillis();
    }

    public boolean isIdleFor(long millis) {
        return System.currentTimeMillis() - lastUsedAtEpochMs >= millis;
    }
}
