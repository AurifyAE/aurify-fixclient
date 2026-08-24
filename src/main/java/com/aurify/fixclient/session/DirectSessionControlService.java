package com.aurify.fixclient.session;

import quickfix.SessionID;

public interface DirectSessionControlService {
    void logon(SessionID sessionId);
    void logout(SessionID sessionId, String reason);
    void start(SessionID sessionId);
    void stop(SessionID sessionId, boolean forceDisconnect);
    void setNextOutboundSeqNum(SessionID sessionId, int seqNum);
    void setNextInboundSeqNum(SessionID sessionId, int seqNum);
    SessionStatusSnapshot statusOf(SessionID sessionId);
}
