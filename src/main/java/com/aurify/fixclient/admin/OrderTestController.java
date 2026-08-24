package com.aurify.fixclient.admin;

import com.aurify.fixclient.canonical.enums.CanonicalSide;
import com.aurify.fixclient.canonical.event.CanonicalOrderRequest;
import com.aurify.fixclient.pipeline.OutboundRequestQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/** Manual/test endpoint to push a single order through the real pipeline
 *  (adapter validation -> FIX build -> send) via a simple POST call. */
@RestController
@RequestMapping("/admin/test-order")
@RequiredArgsConstructor
public class OrderTestController {

    private final OutboundRequestQueue outboundRequestQueue;

    public record TestOrderRequest(
            String provider,
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
        CanonicalOrderRequest order = CanonicalOrderRequest.builder()
                .provider(request.provider())
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

        boolean accepted = outboundRequestQueue.offer(order);
        return accepted
                ? ResponseEntity.ok("Order queued: " + order.getClOrdId())
                : ResponseEntity.status(503).body("Queue full - order not accepted");
    }
}