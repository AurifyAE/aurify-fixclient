package com.yourorg.fixgateway.events;

public record ProviderHealthChangedEvent(String provider, boolean healthy, String reason) {}
