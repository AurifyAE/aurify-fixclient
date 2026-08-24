package com.yourorg.fixgateway.canonical.event;

import java.time.Instant;

public interface CanonicalEvent {
    String provider();
    Instant occurredAt();
}
