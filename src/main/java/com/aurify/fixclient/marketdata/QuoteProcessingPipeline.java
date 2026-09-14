package com.aurify.fixclient.marketdata;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

/**
 * Mirrors {@code InboundProcessingPipeline}, but for quotes: no persistence
 * (per FIX-GATEWAY-ARCHITECTURE.md, market-data persistence should be
 * sampled/off by default, so this pipeline simply does not call into
 * PersistenceGateway) and no Spring event bus - {@code LpQuoteRegistry} is
 * called directly, since quotes have exactly one consumer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuoteProcessingPipeline {

    private final QuoteMessageQueue quoteMessageQueue;
    private final LpQuoteRegistry quoteRegistry;

    @PostConstruct
    void subscribe() {
        quoteMessageQueue.stream()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(quoteRegistry::record)
                .doOnError(e -> log.error("Quote pipeline error", e))
                .retry()
                .subscribe();
    }
}
