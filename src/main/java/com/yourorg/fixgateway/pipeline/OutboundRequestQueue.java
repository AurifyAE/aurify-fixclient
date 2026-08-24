package com.yourorg.fixgateway.pipeline;

import com.yourorg.fixgateway.canonical.event.CanonicalOutboundRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Slf4j
@Component
public class OutboundRequestQueue {

    private final Sinks.Many<CanonicalOutboundRequest> sink =
            Sinks.many().multicast().onBackpressureBuffer(5_000, false);

    public boolean offer(CanonicalOutboundRequest request) {
        Sinks.EmitResult result = sink.tryEmitNext(request);
        if (result.isFailure()) {
            log.error("Outbound queue offer failed for provider {}: {}", request.provider(), result);
            return false;
        }
        return true;
    }

    public Flux<CanonicalOutboundRequest> stream() {
        return sink.asFlux();
    }
}
