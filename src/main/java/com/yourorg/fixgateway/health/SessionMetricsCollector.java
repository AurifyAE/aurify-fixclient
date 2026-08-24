package com.yourorg.fixgateway.health;

import com.yourorg.fixgateway.canonical.event.CanonicalEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class SessionMetricsCollector {

    private final MeterRegistry meterRegistry;

    public SessionMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @EventListener
    public void onCanonicalEvent(CanonicalEvent event) {
        Counter.builder("fixgateway.messages.count")
                .tag("provider", event.provider())
                .tag("type", event.getClass().getSimpleName())
                .register(meterRegistry)
                .increment();
    }
}
