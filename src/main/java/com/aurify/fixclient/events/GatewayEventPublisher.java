package com.aurify.fixclient.events;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Thin wrapper over Spring's ApplicationEventPublisher so internal
 *  consumers (admin UI, downstream systems, metrics) can subscribe via
 *  @EventListener without depending on Reactor or QuickFIX types directly. */
@Component
@RequiredArgsConstructor
public class GatewayEventPublisher {

    private final ApplicationEventPublisher springPublisher;

    public void publish(Object event) {
        springPublisher.publishEvent(event);
    }
}
