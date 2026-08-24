package com.yourorg.fixgateway.canonical.event;

import com.yourorg.fixgateway.canonical.enums.CanonicalExecType;
import com.yourorg.fixgateway.canonical.enums.CanonicalOrdStatus;
import com.yourorg.fixgateway.canonical.enums.CanonicalSide;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

@Value
@Builder
public class CanonicalExecutionReport implements CanonicalEvent {
    String provider;
    String orderId;
    String clOrdId;
    String execId;
    CanonicalExecType execType;
    CanonicalOrdStatus ordStatus;
    String symbol;
    CanonicalSide side;
    BigDecimal orderQty;
    BigDecimal price;
    BigDecimal lastQty;
    BigDecimal lastPx;
    BigDecimal leavesQty;
    BigDecimal cumQty;
    BigDecimal avgPx;
    String rejectText;
    Integer ordRejReason;
    String secondaryClOrdId;
    Instant occurredAt;

    @Override
    public String provider() { return provider; }

    @Override
    public Instant occurredAt() { return occurredAt; }
}
