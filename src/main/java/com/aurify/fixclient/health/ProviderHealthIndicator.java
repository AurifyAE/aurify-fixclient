package com.aurify.fixclient.health;

import com.aurify.fixclient.session.DirectSessionControlService;
import com.aurify.fixclient.session.LpSessionEntry;
import com.aurify.fixclient.session.LpSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports every session the gateway currently holds, for whichever providers
 * the caller has used - no provider name is hardcoded here.
 *
 * Holding no sessions is UP, not DOWN: with lazy logon that is the normal
 * state of an idle gateway, and reporting it as a failure would take a healthy
 * instance out of a load balancer.
 */
@Component
@RequiredArgsConstructor
public class ProviderHealthIndicator implements HealthIndicator {

    private final LpSessionRegistry sessionRegistry;
    private final DirectSessionControlService sessionControl;

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        boolean anyDown = false;

        for (LpSessionEntry entry : sessionRegistry.all()) {
            boolean loggedOn = sessionControl.statusOf(entry.sessionId()).isLoggedOn();
            builder.withDetail(entry.lpAccountId() + " [" + entry.role() + "]",
                    loggedOn ? "UP" : entry.state().name());
            anyDown |= !loggedOn;
        }

        builder.withDetail("sessionCount", sessionRegistry.all().size());
        return anyDown ? builder.down().build() : builder.build();
    }
}
