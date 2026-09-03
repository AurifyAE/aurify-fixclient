package com.aurify.fixclient.canonical.event;

import com.aurify.fixclient.canonical.enums.CanonicalSide;
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
    /** NewOrderSingle PartyID (tag 448) - required by providers that mandate a
     *  NoPartyIDs group (e.g. Finalto). Null/blank: the adapter omits the group. */
    String partyId;

    public enum OrdType { MARKET, LIMIT }
    public enum TimeInForce { IOC, FOK, GTC, DAY }

    @Override
    public String provider() { return provider; }
}
