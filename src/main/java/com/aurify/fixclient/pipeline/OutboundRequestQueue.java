package com.aurify.fixclient.pipeline;

import com.aurify.fixclient.dispatch.OutboundDispatchEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Slf4j
@Component
public class OutboundRequestQueue {

    private final Sinks.Many<OutboundDispatchEnvelope> sink =
            Sinks.many().multicast().onBackpressureBuffer(5_000, false);

    public boolean offer(OutboundDispatchEnvelope envelope) {
        Sinks.EmitResult result = sink.tryEmitNext(envelope);
        if (result.isFailure()) {
            log.error("Outbound queue offer failed for LP account {}: {}", envelope.lpAccountId(), result);
            return false;
        }
        return true;
    }

    public Flux<OutboundDispatchEnvelope> stream() {
        return sink.asFlux();
    }
}
