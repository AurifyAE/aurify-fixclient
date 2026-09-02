package com.aurify.fixclient.pipeline;

import com.aurify.fixclient.dispatch.OutboundFixDispatchService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboundDispatchPipeline {

    private final OutboundRequestQueue outboundRequestQueue;
    private final OutboundFixDispatchService dispatchService;

    @PostConstruct
    void subscribe() {
        outboundRequestQueue.stream()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(envelope -> dispatchService.dispatch(
                        envelope.request(), envelope.policy(), envelope.sessionId(), envelope.lpAccountId()))
                .doOnError(e -> log.error("Outbound pipeline error", e))
                .retry()
                .subscribe();
    }
}
