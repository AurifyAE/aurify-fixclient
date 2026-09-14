package com.aurify.fixclient.marketdata;

import com.aurify.fixclient.canonical.event.CanonicalEvent;
import com.aurify.fixclient.pipeline.InboundEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import quickfix.SessionID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Bounded, non-blocking inbound queue for market data only.
 *
 * Kept entirely separate from {@code InboundMessageQueue}: quotes are
 * high-frequency and, unlike an execution report, a dropped one is
 * inconsequential (the next tick supersedes it) - sharing a queue with
 * execution reports would let a burst of quotes crowd out something that
 * actually must not be lost. Dropping here is silent by design; log noise on
 * every dropped tick would defeat the point of dropping cheaply.
 */
@Slf4j
@Component
public class QuoteMessageQueue {

    private final Sinks.Many<InboundEvent> sink =
            Sinks.many().multicast().onBackpressureBuffer(2_000, false);

    public void offer(SessionID sessionId, String rawFix, CanonicalEvent event) {
        sink.tryEmitNext(new InboundEvent(sessionId, rawFix, event));
        // Failure (buffer full or no subscriber) is expected and fine to drop -
        // see class javadoc.
    }

    public Flux<InboundEvent> stream() {
        return sink.asFlux();
    }
}
