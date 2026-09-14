package com.aurify.fixclient.marketdata;

import com.aurify.fixclient.session.DynamicSessionManager;
import com.aurify.fixclient.session.SessionRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts active StreamQuotes callers per LP account, and closes the pricing
 * session once the count reaches zero.
 *
 * A pricing session exists only to serve subscribers - unlike a trading
 * session, it has no orders to keep it warm for, and if it were left open by
 * idle timeout alone, a continuous quote stream would keep "touching" it
 * forever even after every subscriber disconnected. Closing it deterministically
 * here, rather than relying on {@code IdleSessionReaper}, avoids that leak.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PricingSessionSubscribers {

    private final DynamicSessionManager sessionManager;

    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    /** Call once a StreamQuotes call has an ensured, logged-on pricing session. */
    public void register(String lpAccountId) {
        int count = counts.computeIfAbsent(lpAccountId, k -> new AtomicInteger()).incrementAndGet();
        log.debug("Pricing subscriber registered for LP account {} (now {})", lpAccountId, count);
    }

    /** Call when a StreamQuotes call ends, however it ends (cancel, error, or
     *  completion). */
    public void unregister(String lpAccountId) {
        AtomicInteger counter = counts.get(lpAccountId);
        if (counter == null) {
            return;
        }
        int remaining = counter.decrementAndGet();
        if (remaining <= 0) {
            counts.remove(lpAccountId, counter);
            if (sessionManager.closeSession(lpAccountId, SessionRole.PRICING)) {
                log.info("Closed pricing session for LP account {} - no subscribers left", lpAccountId);
            }
        }
    }
}
