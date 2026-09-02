package com.aurify.fixclient.transport;

import com.aurify.fixclient.config.SessionMetadataRegistry;
import com.aurify.fixclient.events.GatewayEventPublisher;
import com.aurify.fixclient.events.SessionConnectedEvent;
import com.aurify.fixclient.events.SessionDisconnectedEvent;
import com.aurify.fixclient.provider.ProviderAdapterRegistry;
import com.aurify.fixclient.session.DirectSessionControlService;
import com.aurify.fixclient.session.LpSessionEntry;
import com.aurify.fixclient.session.LpSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import quickfix.*;
import quickfix.field.MsgType;
import quickfix.field.Password;
import quickfix.field.Username;

/**
 * Thin QuickFIX/J Application implementation.
 *
 * RULE: no business/provider-specific parsing here. Every callback either
 * does transport bookkeeping or immediately delegates to the cracker /
 * async pipeline. Nothing here blocks the QuickFIX network thread.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayFixApplication implements Application {

    private final LpSessionRegistry lpSessionRegistry;
    private final ProviderAdapterRegistry adapterRegistry;
    private final GatewayMessageCracker messageCracker;
    private final GatewayEventPublisher eventPublisher;
    private final DirectSessionControlService sessionControl;
    private final SessionMetadataRegistry sessionMetadataRegistry;

    @Override
    public void onCreate(SessionID sessionId) {
        // The registry entry is written by DynamicSessionManager before the
        // session is created, so it is already present here.
        log.info("Session created: {}", sessionId);
    }

    @Override
    public void onLogon(SessionID sessionId) {
        log.info("Logon: {}", sessionId);
        lpSessionRegistry.findBySessionId(sessionId).ifPresent(LpSessionEntry::markLoggedOn);
        eventPublisher.publish(new SessionConnectedEvent(sessionId));
        adapterRegistry.resolveForSession(sessionId)
                .ifPresent(adapter -> adapter.onPostLogonStartup(sessionId, sessionControl));
    }

    @Override
    public void onLogout(SessionID sessionId) {
        log.info("Logout: {}", sessionId);
        // Keep the entry: QuickFIX reconnects on its own, and the order path
        // calls ensureSession anyway, which rebuilds it if it does not come back.
        lpSessionRegistry.findBySessionId(sessionId).ifPresent(LpSessionEntry::markDisconnected);
        eventPublisher.publish(new SessionDisconnectedEvent(sessionId));
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        // transport-only: inject credentials on outgoing Logon, nothing else
        try {
            if (MsgType.LOGON.equals(message.getHeader().getString(MsgType.FIELD))) {
                sessionMetadataRegistry.get(sessionId).ifPresent(meta -> {
                    if (meta.getUsername() != null && !meta.getUsername().isEmpty()) {
                        message.setField(new Username(meta.getUsername()));
                    }
                    if (meta.getPassword() != null && !meta.getPassword().isEmpty()) {
                        message.setField(new Password(meta.getPassword()));
                    }
                });
                // Logon carries tag 554 - never log the message body.
                log.debug("[{}] {} : Logon (credentials redacted)",
                        FixMessageDirection.OUTBOUND_ADMIN, sessionId);
                return;
            }
        } catch (FieldNotFound e) {
            log.error("Missing MsgType on outgoing admin message for {}", sessionId, e);
        }
        logDirection(message, sessionId, FixMessageDirection.OUTBOUND_ADMIN);
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
        logDirection(message, sessionId, FixMessageDirection.INBOUND_ADMIN);
    }

    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend {
        logDirection(message, sessionId, FixMessageDirection.OUTBOUND_APP);
    }

    @Override
    public void fromApp(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        logDirection(message, sessionId, FixMessageDirection.INBOUND_APP);
        // delegate immediately — cracking, mapping, and enqueueing happen
        // off this thread inside the cracker/pipeline, never here.
        messageCracker.crack(message, sessionId);
    }

    private void logDirection(Message message, SessionID sessionId, FixMessageDirection direction) {
        log.debug("[{}] {} : {}", direction, sessionId, message);
    }
}
