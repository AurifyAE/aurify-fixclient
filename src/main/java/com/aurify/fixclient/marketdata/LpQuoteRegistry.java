package com.aurify.fixclient.marketdata;

import com.aurify.fixclient.canonical.event.CanonicalQuote;
import com.aurify.fixclient.canonical.event.CanonicalReject;
import com.aurify.fixclient.pipeline.InboundEvent;
import com.aurify.fixclient.session.LpSessionEntry;
import com.aurify.fixclient.session.LpSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Attributes incoming quotes to an LP account and fans them out to gRPC
 * subscribers, the market-data equivalent of {@code ExecutionJournal} - but
 * live-only: a quote that arrives with nobody subscribed is simply gone,
 * there is no replay buffer, because a stale quote is worse than none.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LpQuoteRegistry {

    public record AttributedQuote(String lpAccountId, CanonicalQuote quote) {}

    /** A subscription the venue refused (35=Y). Carried separately from quotes
     *  because it is terminal for the request it names - there is no price
     *  coming, and a caller that keeps waiting will wait forever. */
    public record AttributedReject(String lpAccountId, CanonicalReject reject) {}

    private final LpSessionRegistry lpSessionRegistry;

    private final Sinks.Many<AttributedQuote> live =
            Sinks.many().multicast().onBackpressureBuffer(1_000, false);

    /** Rejects are rare and must not be dropped the way a stale quote can be. */
    private final Sinks.Many<AttributedReject> rejects =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    /** Account+symbol pairs already announced, so the first tick of a
     *  subscription is visible at INFO without logging every tick. */
    private final Set<String> seenFirstTick = ConcurrentHashMap.newKeySet();

    /** Ticks received per LP account since the last {@link #logTickRates()} run,
     *  so "am I still getting live prices" is answered on a heartbeat instead of
     *  only once, on the very first tick of a subscription. */
    private final Map<String, AtomicLong> tickCounts = new ConcurrentHashMap<>();

    public void record(InboundEvent inbound) {
        String lpAccountId = lpSessionRegistry.findBySessionId(inbound.sessionId())
                .map(LpSessionEntry::lpAccountId)
                .orElse(null);
        if (lpAccountId == null) {
            log.debug("Market data on unregistered session {}", inbound.sessionId());
            return;
        }
        if (inbound.event() instanceof CanonicalQuote quote) {
            if (seenFirstTick.add(lpAccountId + "|" + quote.getSymbol())) {
                log.info("First quote for LP account {} symbol {}: bid={} ask={}",
                        lpAccountId, quote.getSymbol(), quote.getBid(), quote.getAsk());
            }
            tickCounts.computeIfAbsent(lpAccountId, k -> new AtomicLong()).incrementAndGet();
            live.tryEmitNext(new AttributedQuote(lpAccountId, quote));
        } else if (inbound.event() instanceof CanonicalReject reject) {
            log.warn("LP account {} refused a market data subscription: {} ({})",
                    lpAccountId, reject.getText(), reject.getReasonCode());
            rejects.tryEmitNext(new AttributedReject(lpAccountId, reject));
        }
    }

    /** Subscription rejections for one LP account. */
    public Flux<AttributedReject> rejectsFor(String lpAccountId) {
        return rejects.asFlux().filter(ar -> ar.lpAccountId().equals(lpAccountId));
    }

    /** Heartbeat so "are live prices still coming in" has an ongoing answer in
     *  the logs, not just the one-time "First quote" line per account+symbol. A
     *  quiet interval with an active pricing session (0 ticks) is exactly the
     *  "provider is silent" case an operator needs to notice. */
    @Scheduled(fixedDelayString = "${fix-gateway.market-data.tick-rate-log-interval-ms:30000}")
    void logTickRates() {
        tickCounts.forEach((lpAccountId, count) -> {
            long ticks = count.getAndSet(0);
            if (ticks > 0) {
                log.info("LP account {}: {} quote ticks received in the last interval", lpAccountId, ticks);
            } else {
                log.warn("LP account {}: no quote ticks received in the last interval", lpAccountId);
            }
        });
    }

    /** Live quotes for one LP account, optionally narrowed to a symbol set. An
     *  empty set means "every symbol". */
    public Flux<AttributedQuote> streamFor(String lpAccountId, Set<String> symbols) {
        Set<String> wanted = symbols.stream().map(LpQuoteRegistry::normalize).collect(Collectors.toSet());
        return live.asFlux().filter(aq -> {
            if (!aq.lpAccountId().equals(lpAccountId)) {
                return false;
            }
            if (wanted.isEmpty() || wanted.contains(normalize(aq.quote().getSymbol()))) {
                return true;
            }
            log.debug("Dropping quote for LP account {}: symbol {} not in requested {}",
                    lpAccountId, aq.quote().getSymbol(), symbols);
            return false;
        });
    }

    /** "XAU/USD", "xau_usd" and "XAUUSD" are one instrument; an LP is free to
     *  echo a different spelling than the one it was asked for, and an exact
     *  match would silently drop every tick. */
    static String normalize(String symbol) {
        return symbol == null ? "" : symbol.replaceAll("[/_\\-\\s]", "").toUpperCase();
    }
}
