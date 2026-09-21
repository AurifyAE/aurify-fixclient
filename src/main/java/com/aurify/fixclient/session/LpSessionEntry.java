package com.aurify.fixclient.session;

import quickfix.SessionID;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    /** Carried through from the spec so a PRICING session's post-logon
     *  subscribe (which only ever receives a SessionID, not the spec) knows
     *  what to ask the venue for. Unused for TRADING entries. Grows when a
     *  later StreamQuotes call asks for a symbol the session was not opened
     *  with, so a reconnect re-subscribes those too. */
    private final Set<String> allowedSymbols = ConcurrentHashMap.newKeySet();

    private volatile String specFingerprint;
    private volatile SessionState state = SessionState.ABSENT;
    private volatile long loggedOnAtEpochMs;
    private volatile long lastUsedAtEpochMs;

    public LpSessionEntry(String lpAccountId, SessionRole role, SessionID sessionId,
                          String provider, String specFingerprint) {
        this(lpAccountId, role, sessionId, provider, specFingerprint, Set.of());
    }

    public LpSessionEntry(String lpAccountId, SessionRole role, SessionID sessionId,
                          String provider, String specFingerprint, Set<String> allowedSymbols) {
        this.lpAccountId = lpAccountId;
        this.role = role;
        this.sessionId = sessionId;
        this.provider = provider;
        this.specFingerprint = specFingerprint;
        if (allowedSymbols != null) {
            this.allowedSymbols.addAll(allowedSymbols);
        }
        this.lastUsedAtEpochMs = System.currentTimeMillis();
    }

    public String lpAccountId() { return lpAccountId; }
    public SessionRole role() { return role; }
    public SessionID sessionId() { return sessionId; }
    public String provider() { return provider; }
    /** Snapshot - safe to iterate while another call adds symbols. */
    public Set<String> allowedSymbols() { return Set.copyOf(allowedSymbols); }

    /** Records symbols this session must now be subscribed to; returns only
     *  the ones it was not already carrying, i.e. the ones still needing a
     *  MarketDataRequest. */
    public Set<String> addSymbols(Collection<String> symbols) {
        Set<String> added = new LinkedHashSet<>();
        for (String symbol : symbols) {
            if (allowedSymbols.add(symbol)) {
                added.add(symbol);
            }
        }
        return added;
    }

    /** Undoes addSymbols for a symbol whose subscribe failed, so the next call retries it. */
    public void removeSymbol(String symbol) { allowedSymbols.remove(symbol); }
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
