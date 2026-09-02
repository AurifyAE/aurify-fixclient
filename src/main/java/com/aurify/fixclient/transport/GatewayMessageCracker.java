package com.aurify.fixclient.transport;

import com.aurify.fixclient.canonical.event.CanonicalEvent;
import com.aurify.fixclient.pipeline.InboundMessageQueue;
import com.aurify.fixclient.provider.LiquidityProviderAdapter;
import com.aurify.fixclient.provider.ProviderAdapterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.MessageCracker;
import quickfix.SessionID;
import quickfix.fix43.ExecutionReport;
import quickfix.fix43.MarketDataRequestReject;
import quickfix.fix43.MarketDataSnapshotFullRefresh;
import quickfix.fix43.OrderCancelReject;
import quickfix.fix43.Reject;
import quickfix.fix43.BusinessMessageReject;

/**
 * Typed router: each FIX message class is delegated to a dedicated handler
 * path. Extend by overriding additional onMessage(...) overloads — never by
 * adding a switch on MsgType elsewhere in the codebase.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayMessageCracker extends MessageCracker {

    private final ProviderAdapterRegistry adapterRegistry;
    private final InboundMessageQueue inboundMessageQueue;

    public void crack(Message message, SessionID sessionId) {
        try {
            super.crack(message, sessionId); // dispatches to onMessage overloads below via reflection
        } catch (Exception e) {
            log.error("Failed to crack message from {}: {}", sessionId, message, e);
        }
    }

    public void onMessage(ExecutionReport report, SessionID sessionId) throws FieldNotFound {
        enqueue(sessionId, report, adapter -> adapter.mapIncoming(report, sessionId));
    }

    /** Market data is out of scope for this trading-only gateway. Dropped with a
     *  log line rather than routed, so it never reaches a mapper that would
     *  throw on it. */
    public void onMessage(MarketDataSnapshotFullRefresh snapshot, SessionID sessionId) {
        log.debug("Ignoring market data snapshot on {} - gateway is trading-only", sessionId);
    }

    public void onMessage(MarketDataRequestReject reject, SessionID sessionId) throws FieldNotFound {
        enqueue(sessionId, reject, adapter -> adapter.mapIncoming(reject, sessionId));
    }

    public void onMessage(OrderCancelReject reject, SessionID sessionId) throws FieldNotFound {
        enqueue(sessionId, reject, adapter -> adapter.mapIncoming(reject, sessionId));
    }

    public void onMessage(Reject reject, SessionID sessionId) throws FieldNotFound {
        enqueue(sessionId, reject, adapter -> adapter.mapIncoming(reject, sessionId));
    }

    public void onMessage(BusinessMessageReject reject, SessionID sessionId) throws FieldNotFound {
        enqueue(sessionId, reject, adapter -> adapter.mapIncoming(reject, sessionId));
    }

    /** The raw message travels with the event so the execution journal can keep
     *  an auditable record of exactly what the venue sent. */
    private void enqueue(SessionID sessionId, Message raw, IncomingMapperFn mapperFn) {
        adapterRegistry.resolveForSession(sessionId).ifPresentOrElse(
                adapter -> {
                    try {
                        CanonicalEvent event = mapperFn.map(adapter);
                        inboundMessageQueue.offer(sessionId, raw.toString(), event);
                    } catch (FieldNotFound e) {
                        log.error("Field missing while mapping inbound message for {}", sessionId, e);
                    }
                },
                () -> log.error("No provider adapter registered for session {}", sessionId)
        );
    }

    @FunctionalInterface
    private interface IncomingMapperFn {
        CanonicalEvent map(LiquidityProviderAdapter adapter) throws FieldNotFound;
    }
}
