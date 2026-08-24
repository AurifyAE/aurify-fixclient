package com.aurify.fixclient.canonical.event;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class CanonicalReject implements CanonicalEvent {
    String provider;
    String refId;        // e.g. MDReqID or ClOrdID this reject refers to
    String reasonCode;
    String text;
    Instant occurredAt;

    @Override
    public String provider() { return provider; }

    @Override
    public Instant occurredAt() { return occurredAt; }
}
