package com.aurify.fixclient.canonical.event;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

/** One top-of-book tick from an LP's pricing session, provider-neutral.
 *  Either side may be null - a one-sided market is legitimate FIX, not an
 *  error, and the caller decides what to do with a missing bid or ask. */
@Value
@Builder
public class CanonicalQuote implements CanonicalEvent {
    String provider;
    String symbol;
    BigDecimal bid;
    BigDecimal bidSize;
    BigDecimal ask;
    BigDecimal askSize;
    String quoteId;
    Instant occurredAt;

    @Override
    public String provider() { return provider; }

    @Override
    public Instant occurredAt() { return occurredAt; }
}
