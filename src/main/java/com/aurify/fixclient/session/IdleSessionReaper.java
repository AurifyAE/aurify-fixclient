package com.aurify.fixclient.session;

import com.aurify.fixclient.config.SessionLifecycleProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Logs out sessions nobody has traded on for a while, so the gateway does not
 * hold open TCP sessions against every LP that ever sent one order.
 *
 * Dropping a session is always safe: the order path calls ensureSession, which
 * rebuilds it. Sequence numbers survive in the file store.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdleSessionReaper {

    private final LpSessionRegistry lpSessionRegistry;
    private final DynamicSessionManager sessionManager;
    private final SessionLifecycleProperties lifecycleProperties;

    @Scheduled(fixedDelayString = "${fix-gateway.session.reaper-interval-ms:60000}")
    public void reapIdleSessions() {
        int idleMinutes = lifecycleProperties.getIdleTimeoutMinutes();
        if (idleMinutes <= 0) {
            return; // reaping disabled - sessions stay up until they fail
        }

        long idleMillis = idleMinutes * 60_000L;
        for (LpSessionEntry entry : lpSessionRegistry.all()) {
            if (!entry.isIdleFor(idleMillis)) {
                continue;
            }
            log.info("LP account {} [{}] idle for over {} minute(s) - closing session",
                    entry.lpAccountId(), entry.role(), idleMinutes);
            sessionManager.closeSession(entry.lpAccountId(), entry.role());
        }
    }
}
