package com.aurify.fixclient.provider.finalto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

/**
 * Post-logon behaviour for Finalto (CFH). No-op for the same reason as
 * {@code FxCubicStartupWorkflow}: the gateway runs trading sessions only, and
 * market data (Finalto's Pricing session) is out of scope.
 */
@Slf4j
@Component
public class FinaltoStartupWorkflow {

    public void run(SessionID sessionId) {
        log.debug("Post-logon startup for {}: nothing to do (trading-only gateway)", sessionId);
    }
}
