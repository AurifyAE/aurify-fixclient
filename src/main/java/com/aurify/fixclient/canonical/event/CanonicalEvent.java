package com.aurify.fixclient.canonical.event;

import java.time.Instant;

public interface CanonicalEvent {
    String provider();
    Instant occurredAt();
}
