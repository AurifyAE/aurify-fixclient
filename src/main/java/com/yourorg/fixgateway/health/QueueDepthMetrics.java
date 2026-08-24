package com.yourorg.fixgateway.health;

import com.yourorg.fixgateway.pipeline.InboundMessageQueue;
import com.yourorg.fixgateway.pipeline.OutboundRequestQueue;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** Reactor Sinks don't expose depth directly in all configurations - in a
 *  real implementation, wrap offer()/consume() with AtomicLong counters and
 *  register gauges here rather than relying on internal Sinks state. */
@Component
@RequiredArgsConstructor
public class QueueDepthMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicLong inboundDepth = new AtomicLong();
    private final AtomicLong outboundDepth = new AtomicLong();

    @PostConstruct
    void registerGauges() {
        meterRegistry.gauge("fixgateway.queue.depth", java.util.List.of(
                io.micrometer.core.instrument.Tag.of("queue", "inbound")), inboundDepth);
        meterRegistry.gauge("fixgateway.queue.depth", java.util.List.of(
                io.micrometer.core.instrument.Tag.of("queue", "outbound")), outboundDepth);
    }
}
