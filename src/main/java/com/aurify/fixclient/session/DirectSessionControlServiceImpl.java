package com.aurify.fixclient.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import quickfix.Session;
import quickfix.SessionID;

import java.io.IOException;
import java.time.Instant;
import java.util.function.Consumer;

@Slf4j
@Service
public class DirectSessionControlServiceImpl implements DirectSessionControlService {

    @Override
    public void logon(SessionID sessionId) {
        withSession(sessionId, Session::logon);
    }

    @Override
    public void logout(SessionID sessionId, String reason) {
        withSession(sessionId, s -> s.logout(reason));
    }

    @Override
    public void start(SessionID sessionId) {
        // lifecycle owned by QuickFIX/J SocketInitiator; exposed here for admin API symmetry
        log.info("Start requested for {}", sessionId);
    }

    @Override
    public void stop(SessionID sessionId, boolean forceDisconnect) {
        withSession(sessionId, s -> {
            try {
                s.disconnect("Admin requested stop", forceDisconnect);
            } catch (IOException e) {
                log.error("Failed to disconnect {}", sessionId, e);
            }
        });
    }

    @Override
    public void setNextOutboundSeqNum(SessionID sessionId, int seqNum) {
        withSession(sessionId, s -> {
            try {
                s.setNextSenderMsgSeqNum(seqNum);
            } catch (IOException e) {
                log.error("Failed to set outbound seq for {}", sessionId, e);
            }
        });
    }

    @Override
    public void setNextInboundSeqNum(SessionID sessionId, int seqNum) {
        withSession(sessionId, s -> {
            try {
                s.setNextTargetMsgSeqNum(seqNum);
            } catch (IOException e) {
                log.error("Failed to set inbound seq for {}", sessionId, e);
            }
        });
    }

    @Override
    public SessionStatusSnapshot statusOf(SessionID sessionId) {
        Session session = Session.lookupSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Unknown session: " + sessionId);
        }
        return SessionStatusSnapshot.builder()
                .loggedOn(session.isLoggedOn())
                .nextInboundSeqNum(session.getExpectedTargetNum())
                .nextOutboundSeqNum(session.getExpectedSenderNum())
                .lastLogonTime(Instant.now()) // wire to a session store for real timestamps
                .build();
    }

    private void withSession(SessionID sessionId, Consumer<Session> action) {
        Session session = Session.lookupSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Unknown session: " + sessionId);
        }
        action.accept(session);
    }
}
