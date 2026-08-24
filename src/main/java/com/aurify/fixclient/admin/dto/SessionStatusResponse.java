package com.aurify.fixclient.admin.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SessionStatusResponse {
    String provider;
    String role;
    String sessionId;
    boolean loggedOn;
    int nextInboundSeqNum;
    int nextOutboundSeqNum;
}
