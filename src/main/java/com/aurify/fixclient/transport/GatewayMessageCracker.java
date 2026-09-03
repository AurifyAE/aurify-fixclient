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
import quickfix.SessionID;
import quickfix.field.MsgType;

/**
 * Routes an inbound application message by MsgType (tag 35), not by its typed
 * FIX-version class. {@code LiquidityProviderAdapter.mapIncoming} already
 * takes a version-neutral {@code Message} - it was the old {@code
 * MessageCracker}-based dispatch here, reflecting on {@code
 * quickfix.fix43.*} classes, that forced a new typed {@code onMessage}
 * overload (and a touch of this file) for every FIX version a new LP might
 * speak. Adding FIX 4.4 (Finalto) needs none, and no future version will
 * either: a version's message classes only need to be on the classpath
 * (added in pom.xml) for {@code DefaultMessageFactory} to construct them and
 * the resolved dictionary to validate them, both upstream of this router.
 *
 * MsgTypes this gateway cares about are routed to the session's provider
 * adapter; everything else (market data, and anything not yet handled) is
 * dropped with a debug log line rather than thrown - an unhandled MsgType
 * must never look like a mapping failure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayMessageCracker {

    private final ProviderAdapterRegistry adapterRegistry;
    private final InboundMessageQueue inboundMessageQueue;

    public void crack(Message message, SessionID sessionId) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            switch (msgType) {
                case MsgType.EXECUTION_REPORT,
                     MsgType.ORDER_CANCEL_REJECT,
                     MsgType.REJECT,
                     MsgType.BUSINESS_MESSAGE_REJECT,
                     MsgType.MARKET_DATA_REQUEST_REJECT ->
                        enqueue(sessionId, message, adapter -> adapter.mapIncoming(message, sessionId));
                case MsgType.MARKET_DATA_SNAPSHOT_FULL_REFRESH ->
                        log.debug("Ignoring market data snapshot on {} - gateway is trading-only", sessionId);
                default ->
                        log.debug("Ignoring unhandled MsgType {} on {}", msgType, sessionId);
            }
        } catch (Exception e) {
            log.error("Failed to crack message from {}: {}", sessionId, message, e);
        }
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
