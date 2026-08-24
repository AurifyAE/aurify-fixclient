package com.yourorg.fixgateway.canonical.event;

import com.yourorg.fixgateway.canonical.enums.CanonicalSide;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/** Outbound canonical request, provider-agnostic. Converted to FIX late,
 *  inside the resolved provider adapter's buildOutgoing(). */
@Value
@Builder
public class CanonicalOrderRequest implements CanonicalOutboundRequest {
    String provider;
    String clOrdId;
    String account;
    String ticketId;
    String group;
    String symbol;
    CanonicalSide side;
    BigDecimal orderQty;
    OrdType ordType;
    BigDecimal price;
    TimeInForce timeInForce;

    public enum OrdType { MARKET, LIMIT }
    public enum TimeInForce { IOC, FOK, GTC, DAY }

    @Override
    public String provider() { return provider; }
}
