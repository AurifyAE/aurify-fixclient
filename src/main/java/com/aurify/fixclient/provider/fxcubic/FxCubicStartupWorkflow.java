package com.aurify.fixclient.provider.fxcubic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

/**
 * Post-logon behaviour for FXCubic.
 *
 * Intentionally a no-op: the gateway currently runs trading sessions only, and
 * market-data subscription is out of scope. The previous implementation
 * subscribed a hardcoded FX-pair list on every session regardless of role,
 * which sent MarketDataRequests down trading sessions.
 *
 * When market data is added back, take the symbols from the caller's session
 * spec and subscribe only on a PRICING session.
 */
@Slf4j
@Component
public class FxCubicStartupWorkflow {

    public void run(SessionID sessionId) {
        log.debug("Post-logon startup for {}: nothing to do (trading-only gateway)", sessionId);
    }
}
