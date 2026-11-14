package com.aurify.fixclient.grpc;

import aurify.lphedge.v1.SubmitMarketOrderRequest;
import com.aurify.fixclient.canonical.event.CanonicalOrderRequest;
import com.aurify.fixclient.session.LpSessionSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The order type decides what the venue may do with the caller's money: a limit
 * order must reach the adapter with its price, and a request that is ambiguous
 * about its price must never go out as a market order.
 */
class LpHedgeGatewayServiceOrderTypeTest {

    private final LpHedgeGatewayService service =
            new LpHedgeGatewayService(null, null, new GrpcGatewayProperties(), null, null, null, null, null);

    private static SubmitMarketOrderRequest.Builder request() {
        return SubmitMarketOrderRequest.newBuilder()
                .setIdempotencyKey("lp-hedge:1")
                .setOrderId("order-1")
                .setLpAccountId("acct-1")
                .setSymbol("XAUUSD")
                .setSide("BUY")
                .setQuantity(5)
                .setSession(aurify.lphedge.v1.LpSessionSpec.getDefaultInstance());
    }

    private static LpSessionSpec spec() {
        return new LpSessionSpec("acct-1", "FXCubic", "FIX.4.3",
                new LpSessionSpec.FixSessionSpec("h", 1, "A", "B", "u", "p", true, false, 30, "", "", ""),
                Set.of("XAUUSD"), 500L, 1000D, 5000D, "fp-1", "LP-ACC-1", "", null, "");
    }

    private CanonicalOrderRequest canonical(SubmitMarketOrderRequest.Builder builder) {
        SubmitMarketOrderRequest request = builder.build();
        return service.toCanonicalOrder(request, spec(), "order-1", LpHedgeGatewayService.resolveOrderQty(request));
    }

    private void assertRefused(SubmitMarketOrderRequest.Builder builder, String code) {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> service.validate(builder.build()));
        // Node reads the code from the first word of the status details.
        assertTrue(refused.getMessage().startsWith(code + " "), refused.getMessage());
    }

    @Test
    void anEmptyOrderTypeStaysAMarketOrder() {
        CanonicalOrderRequest order = canonical(request());

        assertEquals(CanonicalOrderRequest.OrdType.MARKET, order.getOrdType());
        assertNull(order.getPrice());
    }

    @Test
    void aLimitOrderCarriesItsExactPrice() {
        CanonicalOrderRequest order = canonical(request().setOrderType("limit").setLimitPrice("2345.67"));

        assertEquals(CanonicalOrderRequest.OrdType.LIMIT, order.getOrdType());
        assertEquals(new BigDecimal("2345.67"), order.getPrice());
    }

    @Test
    void aLimitOrderKeepsAFractionalVenueQuantity() {
        CanonicalOrderRequest order = canonical(request()
                .setQuantityDecimal("3.21").setOrderType("LIMIT").setLimitPrice("2345.67"));

        assertEquals(new BigDecimal("3.21"), order.getOrderQty());
        assertEquals(new BigDecimal("2345.67"), order.getPrice());
    }

    @Test
    void aValidLimitOrderPassesValidation() {
        assertDoesNotThrow(() -> service.validate(request().setOrderType("LIMIT").setLimitPrice("2345.67").build()));
    }

    @Test
    void aLimitOrderWithoutAPriceIsRefused() {
        assertRefused(request().setOrderType("LIMIT"), "INVALID_LIMIT_PRICE");
        assertRefused(request().setOrderType("LIMIT").setLimitPrice("  "), "INVALID_LIMIT_PRICE");
    }

    @Test
    void aLimitPriceThatIsNotAPositiveNumberIsRefused() {
        assertRefused(request().setOrderType("LIMIT").setLimitPrice("abc"), "INVALID_LIMIT_PRICE");
        assertRefused(request().setOrderType("LIMIT").setLimitPrice("NaN"), "INVALID_LIMIT_PRICE");
        assertRefused(request().setOrderType("LIMIT").setLimitPrice("0"), "INVALID_LIMIT_PRICE");
        assertRefused(request().setOrderType("LIMIT").setLimitPrice("-1"), "INVALID_LIMIT_PRICE");
    }

    @Test
    void aMarketOrderWithAPriceIsRefusedRatherThanSentWithoutItsCap() {
        assertRefused(request().setLimitPrice("2345.67"), "INVALID_LIMIT_PRICE");
        assertRefused(request().setOrderType("MARKET").setLimitPrice("2345.67"), "INVALID_LIMIT_PRICE");
    }

    @Test
    void anUnknownOrderTypeIsRefused() {
        assertRefused(request().setOrderType("STOP"), "INVALID_ORDER_TYPE");
    }
}
