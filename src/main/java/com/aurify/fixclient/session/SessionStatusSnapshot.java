package com.aurify.fixclient.session;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class SessionStatusSnapshot {
    String provider;
    SessionRole role;
    boolean loggedOn;
    int nextInboundSeqNum;
    int nextOutboundSeqNum;
    Instant lastLogonTime;
    Instant lastLogoutTime;
}
