package com.yourorg.fixgateway.transport;

import com.yourorg.fixgateway.config.SessionMetadataRegistry;
import com.yourorg.fixgateway.events.GatewayEventPublisher;
import com.yourorg.fixgateway.events.SessionConnectedEvent;
import com.yourorg.fixgateway.events.SessionDisconnectedEvent;
import com.yourorg.fixgateway.provider.ProviderAdapterRegistry;
import com.yourorg.fixgateway.session.DirectSessionControlService;
import com.yourorg.fixgateway.session.ProviderSessionRegistry;
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

    private final ProviderSessionRegistry sessionRegistry;
    private final ProviderAdapterRegistry adapterRegistry;
    private final GatewayMessageCracker messageCracker;
    private final GatewayEventPublisher eventPublisher;
    private final DirectSessionControlService sessionControl;
    private final SessionMetadataRegistry sessionMetadataRegistry;

    @Override
    public void onCreate(SessionID sessionId) {
        log.info("Session created: {}", sessionId);
        sessionMetadataRegistry.get(sessionId).ifPresentOrElse(
                meta -> sessionRegistry.register(meta.getProviderName(), meta.getRole(), sessionId),
                () -> log.warn("No SessionMetadata found for {} - was it configured in application.yml?", sessionId)
        );
    }

    @Override
    public void onLogon(SessionID sessionId) {
        log.info("Logon: {}", sessionId);
        eventPublisher.publish(new SessionConnectedEvent(sessionId));
        adapterRegistry.resolveForSession(sessionId)
                .ifPresent(adapter -> adapter.onPostLogonStartup(sessionId, sessionControl));
    }

    @Override
    public void onLogout(SessionID sessionId) {
        log.info("Logout: {}", sessionId);
        eventPublisher.publish(new SessionDisconnectedEvent(sessionId));
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        // transport-only: inject credentials on outgoing Logon, nothing else
        try {
            if (MsgType.LOGON.equals(message.getHeader().getString(MsgType.FIELD))) {
                sessionMetadataRegistry.get(sessionId).ifPresent(meta -> {
                    if (meta.getUsername() != null) {
                        message.setField(new Username(meta.getUsername()));
                    }
                    if (meta.getPassword() != null) {
                        message.setField(new Password(meta.getPassword()));
                    }
                });
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
