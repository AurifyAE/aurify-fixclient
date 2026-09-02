package com.aurify.fixclient.pipeline;

import com.aurify.fixclient.events.GatewayEventPublisher;
import com.aurify.fixclient.persistence.PersistenceGateway;
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
                .doOnNext(inbound -> persistenceGateway.persistCanonicalEvent(inbound.event()))
                // The envelope goes first, and the order matters: it is what the
                // execution journal records from, while the bare event is what
                // completes the waiting gRPC call. Publishing the event first
                // would let a call answer an order before the journal held the
                // report that settled it, so the response could not include it.
                .doOnNext(eventPublisher::publish)
                // The bare canonical event is what every other listener
                // subscribes to, and stays the contract for them.
                .doOnNext(inbound -> eventPublisher.publish(inbound.event()))
                .doOnError(e -> log.error("Inbound pipeline error", e))
                .retry()
                .subscribe();
    }
}
