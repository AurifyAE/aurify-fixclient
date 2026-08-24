package com.yourorg.fixgateway.pipeline;

import com.yourorg.fixgateway.events.GatewayEventPublisher;
import com.yourorg.fixgateway.persistence.PersistenceGateway;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Component
@RequiredArgsConstructor
public class InboundProcessingPipeline {

    private final InboundMessageQueue inboundMessageQueue;
    private final PersistenceGateway persistenceGateway;
    private final GatewayEventPublisher eventPublisher;

    @PostConstruct
    void subscribe() {
        inboundMessageQueue.stream()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(persistenceGateway::persistCanonicalEvent)
                .doOnNext(eventPublisher::publish)
                .doOnError(e -> log.error("Inbound pipeline error", e))
                .retry()
                .subscribe();
    }
}
