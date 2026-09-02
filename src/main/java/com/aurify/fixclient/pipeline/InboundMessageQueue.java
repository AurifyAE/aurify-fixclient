package com.aurify.fixclient.pipeline;

import com.aurify.fixclient.canonical.event.CanonicalEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import quickfix.SessionID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** Bounded, non-blocking inbound queue. Never called from a QuickFIX network
 *  thread except via offer(), which is O(1) and non-blocking. */
@Slf4j
@Component
public class InboundMessageQueue {

    private final Sinks.Many<InboundEvent> sink =
            Sinks.many().multicast().onBackpressureBuffer(10_000, false);

    public void offer(SessionID sessionId, String rawFix, CanonicalEvent event) {
        Sinks.EmitResult result = sink.tryEmitNext(new InboundEvent(sessionId, rawFix, event));
        if (result.isFailure()) {
            log.warn("Inbound queue offer failed for {} ({}): {}", sessionId, event.provider(), result);
        }
    }

    public Flux<InboundEvent> stream() {
        return sink.asFlux();
    }
}
