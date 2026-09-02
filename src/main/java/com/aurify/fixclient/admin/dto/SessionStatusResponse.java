package com.aurify.fixclient.admin.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SessionStatusResponse {
    String lpAccountId;
    String provider;
    String role;
    String state;
    String sessionId;
    String specFingerprint;
    boolean loggedOn;
    int nextInboundSeqNum;
    int nextOutboundSeqNum;
    long loggedOnAtEpochMs;
    long lastUsedAtEpochMs;
    String errorCode;
    String errorMessage;
}
