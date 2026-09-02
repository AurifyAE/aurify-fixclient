package com.aurify.fixclient.session;

import org.springframework.stereotype.Component;
import quickfix.SessionID;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which LP account owns which live FIX session.
 *
 * Replaces the provider+role keying of {@link ProviderSessionRegistry}, which
 * could not represent two accounts on the same provider - the normal case once
 * LP config comes from the caller rather than from application.yml.
 */
@Component
public class LpSessionRegistry {

    private record Key(String lpAccountId, SessionRole role) {}

    private final Map<Key, LpSessionEntry> byAccount = new ConcurrentHashMap<>();
    private final Map<SessionID, LpSessionEntry> bySessionId = new ConcurrentHashMap<>();

    public Optional<LpSessionEntry> find(String lpAccountId, SessionRole role) {
        return Optional.ofNullable(byAccount.get(new Key(lpAccountId, role)));
    }

    public Optional<LpSessionEntry> findBySessionId(SessionID sessionId) {
        return Optional.ofNullable(bySessionId.get(sessionId));
    }

    public void put(LpSessionEntry entry) {
        byAccount.put(new Key(entry.lpAccountId(), entry.role()), entry);
        bySessionId.put(entry.sessionId(), entry);
    }

    public void remove(String lpAccountId, SessionRole role) {
        LpSessionEntry removed = byAccount.remove(new Key(lpAccountId, role));
        if (removed != null) {
            bySessionId.remove(removed.sessionId());
        }
    }

    public Collection<LpSessionEntry> all() {
        return List.copyOf(byAccount.values());
    }

    /**
     * Another LP account already holding a session with these comp IDs.
     *
     * An LP identifies a session by SenderCompID/TargetCompID and typically
     * permits one at a time, so two accounts configured with the same comp IDs
     * cannot both be logged on - the venue simply drops the second connection.
     * The SessionID qualifier keeps them distinct here, which hides the clash
     * unless it is checked for explicitly.
     */
    public Optional<LpSessionEntry> findConflicting(SessionID sessionId, String lpAccountId) {
        return byAccount.values().stream()
                .filter(entry -> !entry.lpAccountId().equals(lpAccountId))
                .filter(entry -> entry.state() == SessionState.LOGGED_ON
                        || entry.state() == SessionState.CONNECTING)
                .filter(entry -> entry.sessionId().getSenderCompID().equals(sessionId.getSenderCompID())
                        && entry.sessionId().getTargetCompID().equals(sessionId.getTargetCompID()))
                .findFirst();
    }
}
