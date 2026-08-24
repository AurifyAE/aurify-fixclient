package com.aurify.fixclient.provider;

import lombok.Builder;
import lombok.Value;

import java.util.Set;

@Value
@Builder
public class ProviderCapabilities {
    String fixVersion;
    boolean supportsPricingSession;
    boolean supportsTradingSession;
    Set<String> supportedOrdTypes;
    Set<String> supportedTimeInForce;
}
