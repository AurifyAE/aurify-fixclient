package com.aurify.fixclient.events;

public record ProviderHealthChangedEvent(String provider, boolean healthy, String reason) {}
