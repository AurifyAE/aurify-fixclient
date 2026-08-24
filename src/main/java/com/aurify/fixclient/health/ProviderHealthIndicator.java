package com.aurify.fixclient.health;

import com.aurify.fixclient.session.DirectSessionControlService;
import com.aurify.fixclient.session.ProviderSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

@Component
@RequiredArgsConstructor
public class ProviderHealthIndicator implements HealthIndicator {

    private final ProviderSessionRegistry sessionRegistry;
    private final DirectSessionControlService sessionControl;

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        boolean anyDown = false;
        for (SessionID sessionId : sessionRegistry.allSessionsFor("fxcubic")) {
            boolean loggedOn = sessionControl.statusOf(sessionId).isLoggedOn();
            builder.withDetail(sessionId.toString(), loggedOn ? "UP" : "DOWN");
            anyDown |= !loggedOn;
        }
        return anyDown ? builder.down().build() : builder.build();
    }
}
