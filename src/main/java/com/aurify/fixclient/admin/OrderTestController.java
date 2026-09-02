package com.aurify.fixclient.admin;

import com.aurify.fixclient.canonical.enums.CanonicalSide;
import com.aurify.fixclient.canonical.event.CanonicalOrderRequest;
import com.aurify.fixclient.dispatch.OutboundDispatchEnvelope;
import com.aurify.fixclient.pipeline.OutboundRequestQueue;
import com.aurify.fixclient.provider.OutboundPolicy;
import com.aurify.fixclient.session.LpSessionEntry;
import com.aurify.fixclient.session.LpSessionRegistry;
import com.aurify.fixclient.session.SessionRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Optional;

/** Manual/test endpoint to push a single order through the real pipeline
 *  (adapter validation -> FIX build -> send) via a simple POST call.
 *
 *  Sends on an existing session only - establish one with the EnsureSession RPC
 *  first, since this endpoint carries no credentials of its own. */
@RestController
@RequestMapping("/admin/test-order")
@RequiredArgsConstructor
public class OrderTestController {

    private final OutboundRequestQueue outboundRequestQueue;
    private final LpSessionRegistry sessionRegistry;

    public record TestOrderRequest(
            String lpAccountId,
            String symbol,
            String side,        // "BUY" or "SELL"
            BigDecimal quantity,
            String ordType,     // "MARKET" or "LIMIT"
            BigDecimal price,   // required if ordType=LIMIT
            String account,
            String ticketId,
            String group
    ) {}

    @PostMapping
    public ResponseEntity<String> placeTestOrder(@RequestBody TestOrderRequest request) {
        Optional<LpSessionEntry> sessionOpt =
                sessionRegistry.find(request.lpAccountId(), SessionRole.TRADING);
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.status(409).body(
                    "No live session for LP account " + request.lpAccountId() + " - call EnsureSession first");
        }
        LpSessionEntry session = sessionOpt.get();

        CanonicalOrderRequest order = CanonicalOrderRequest.builder()
                .provider(session.provider())
                .clOrdId("REST-" + System.currentTimeMillis())
                .account(request.account() != null ? request.account() : "1")
                .ticketId(request.ticketId() != null ? request.ticketId() : "1")
                .group(request.group() != null ? request.group() : "RestTest")
                .symbol(request.symbol())
                .side("SELL".equalsIgnoreCase(request.side()) ? CanonicalSide.SELL : CanonicalSide.BUY)
                .orderQty(request.quantity())
                .ordType("LIMIT".equalsIgnoreCase(request.ordType())
                        ? CanonicalOrderRequest.OrdType.LIMIT : CanonicalOrderRequest.OrdType.MARKET)
                .price(request.price())
                .timeInForce(CanonicalOrderRequest.TimeInForce.IOC)
                .build();

        // No policy is available here - the spec lives with the caller, not the
        // gateway - so this path applies no symbol allowlist or size limit.
        boolean accepted = outboundRequestQueue.offer(new OutboundDispatchEnvelope(
                order, OutboundPolicy.unrestricted(), session.sessionId(), session.lpAccountId()));

        return accepted
                ? ResponseEntity.ok("Order queued: " + order.getClOrdId())
                : ResponseEntity.status(503).body("Queue full - order not accepted");
    }
}
