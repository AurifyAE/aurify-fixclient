package com.aurify.fixclient.config;

import org.springframework.stereotype.Component;
import quickfix.SessionID;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Built once at startup by QuickFixSessionConfigFactory. Lets the thin
 *  QuickFIX Application (onCreate/toAdmin) and the session registry find out
 *  which provider+role a given SessionID belongs to. */
@Component
public class SessionMetadataRegistry {

    private final Map<SessionID, SessionMetadata> metadata = new ConcurrentHashMap<>();

    public void put(SessionID sessionId, SessionMetadata meta) {
        metadata.put(sessionId, meta);
    }

    public Optional<SessionMetadata> get(SessionID sessionId) {
        return Optional.ofNullable(metadata.get(sessionId));
    }
}
