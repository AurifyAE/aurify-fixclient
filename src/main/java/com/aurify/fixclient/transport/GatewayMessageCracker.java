package com.aurify.fixclient.transport;

import com.aurify.fixclient.canonical.event.CanonicalEvent;
import com.aurify.fixclient.marketdata.QuoteMessageQueue;
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
 * adapter; everything else (still-unhandled types) is dropped with a debug
 * log line rather than thrown - an unhandled MsgType must never look like a
 * mapping failure. Market data (35=W/35=X) is routed like every other known
 * type, but into its own queue: quotes are high-frequency and must not share
 * capacity with execution reports.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayMessageCracker {

    private final ProviderAdapterRegistry adapterRegistry;
    private final InboundMessageQueue inboundMessageQueue;
    private final QuoteMessageQueue quoteMessageQueue;

    public void crack(Message message, SessionID sessionId) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            switch (msgType) {
                case MsgType.EXECUTION_REPORT,
                     MsgType.ORDER_CANCEL_REJECT,
                     MsgType.REJECT,
                     MsgType.BUSINESS_MESSAGE_REJECT -> {
                        log.info("Received {} from market on session {}", msgType, sessionId);
                        enqueue(inboundMessageQueue::offer, sessionId, message,
                                adapter -> adapter.mapIncoming(message, sessionId));
                    }
                // A MarketDataRequestReject answers a subscription, never an
                // order - it carries an MDReqID, so nothing in the order path
                // can match it. Routing it with the quotes is what lets a
                // subscription the venue refused surface to the caller waiting
                // for prices, instead of being filed against orders and
                // leaving that caller waiting forever for a tick that the LP
                // has already said it will not send.
                case MsgType.MARKET_DATA_SNAPSHOT_FULL_REFRESH,
                     MsgType.MARKET_DATA_INCREMENTAL_REFRESH,
                     MsgType.MARKET_DATA_REQUEST_REJECT ->
                        enqueue(quoteMessageQueue::offer, sessionId, message,
                                adapter -> adapter.mapIncoming(message, sessionId));
                default ->
                        log.debug("Ignoring unhandled MsgType {} on {}", msgType, sessionId);
            }
        } catch (Exception e) {
            log.error("Failed to crack message from {}: {}", sessionId, message, e);
        }
    }

    /** The raw message travels with the event so the execution journal (or, for
     *  quotes, LpQuoteRegistry) can attribute it to the right LP account. */
    private void enqueue(InboundSink sink, SessionID sessionId, Message raw, IncomingMapperFn mapperFn) {
        adapterRegistry.resolveForSession(sessionId).ifPresentOrElse(
                adapter -> {
                    try {
                        CanonicalEvent event = mapperFn.map(adapter);
                        sink.offer(sessionId, raw.toString(), event);
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

    /** Common shape of InboundMessageQueue/QuoteMessageQueue's offer(), so
     *  enqueue() does not need to know which one it is routing to. */
    @FunctionalInterface
    private interface InboundSink {
        void offer(SessionID sessionId, String rawFix, CanonicalEvent event);
    }
}
