package com.aurify.fixclient.marketdata;

import com.aurify.fixclient.canonical.event.CanonicalQuote;
import com.aurify.fixclient.canonical.event.CanonicalReject;
import com.aurify.fixclient.pipeline.InboundEvent;
import com.aurify.fixclient.session.LpSessionEntry;
import com.aurify.fixclient.session.LpSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Set;

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

    public void record(InboundEvent inbound) {
        String lpAccountId = lpSessionRegistry.findBySessionId(inbound.sessionId())
                .map(LpSessionEntry::lpAccountId)
                .orElse(null);
        if (lpAccountId == null) {
            log.debug("Market data on unregistered session {}", inbound.sessionId());
            return;
        }
        if (inbound.event() instanceof CanonicalQuote quote) {
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

    /** Live quotes for one LP account, optionally narrowed to a symbol set. An
     *  empty set means "every symbol". */
    public Flux<AttributedQuote> streamFor(String lpAccountId, Set<String> symbols) {
        return live.asFlux().filter(aq ->
                aq.lpAccountId().equals(lpAccountId)
                        && (symbols.isEmpty() || symbols.contains(aq.quote().getSymbol())));
    }
}
