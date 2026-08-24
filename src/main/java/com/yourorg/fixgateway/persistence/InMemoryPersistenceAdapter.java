package com.yourorg.fixgateway.persistence;

import com.yourorg.fixgateway.canonical.event.CanonicalEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

import java.util.Collections;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
@Component
@ConditionalOnProperty(name = "fix-gateway.persistence.mode", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryPersistenceAdapter implements PersistenceGateway {

    private static final int RING_BUFFER_SIZE = 5_000;
    private final Deque<String> ring = new ConcurrentLinkedDeque<>();

    @Override
    public void persistRawInbound(SessionID sessionId, String rawFix) {
        offer("IN  " + sessionId + " " + rawFix);
    }

    @Override
    public void persistRawOutbound(SessionID sessionId, String rawFix) {
        offer("OUT " + sessionId + " " + rawFix);
    }

    @Override
    public void persistCanonicalEvent(CanonicalEvent event) {
        offer("EVT " + event.provider() + " " + event);
    }

    @Override
    public void persistFailedPublish(CanonicalEvent event, Throwable cause) {
        log.error("Failed publish persisted: event={} cause={}", event, cause.getMessage());
    }

    private void offer(String entry) {
        ring.addLast(entry);
        while (ring.size() > RING_BUFFER_SIZE) {
            ring.pollFirst();
        }
    }
}
