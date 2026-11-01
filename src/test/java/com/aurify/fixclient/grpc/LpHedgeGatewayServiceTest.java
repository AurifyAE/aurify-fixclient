package com.aurify.fixclient.grpc;

import aurify.lphedge.v1.SubmitMarketOrderRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link LpHedgeGatewayService#resolveOrderQty} carries the whole
 * grams-to-venue-units contract for one order: prefer the decimal field,
 * fall back to the legacy integer, and reject anything the FIX side could
 * not send (non-numeric, non-positive, too many decimal places, or above the
 * caller's per-symbol cap). Tested directly rather than through the full
 * gRPC service because it needs no session, dispatch, or journal to exercise.
 */
class LpHedgeGatewayServiceTest {

    private SubmitMarketOrderRequest.Builder request() {
        return SubmitMarketOrderRequest.newBuilder()
                .setIdempotencyKey("IDEMP-1")
                .setOrderId("ORDER-1")
                .setLpAccountId("LP-1")
                .setSymbol("XAUUSD")
                .setSide("BUY");
    }

    @Test
    void decimalQuantityIsPreferredOverLegacyInteger() {
        BigDecimal qty = LpHedgeGatewayService.resolveOrderQty(
                request().setQuantity(3).setQuantityDecimal("3.21").build());

        assertEquals(new BigDecimal("3.21"), qty);
    }

    @Test
    void legacyIntegerQuantityIsUsedWhenDecimalIsEmpty() {
        BigDecimal qty = LpHedgeGatewayService.resolveOrderQty(
                request().setQuantity(100).build());

        assertEquals(BigDecimal.valueOf(100), qty);
    }

    @Test
    void nonNumericDecimalQuantityIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LpHedgeGatewayService.resolveOrderQty(
                        request().setQuantityDecimal("not-a-number").build()));

        assertEquals(true, e.getMessage().startsWith("INVALID_QUANTITY"));
    }

    @Test
    void zeroQuantityIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LpHedgeGatewayService.resolveOrderQty(
                        request().setQuantityDecimal("0").build()));

        assertEquals(true, e.getMessage().startsWith("INVALID_QUANTITY"));
    }

    @Test
    void negativeQuantityIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LpHedgeGatewayService.resolveOrderQty(
                        request().setQuantityDecimal("-1.5").build()));

        assertEquals(true, e.getMessage().startsWith("INVALID_QUANTITY"));
    }

    @Test
    void scaleAboveEightDecimalPlacesIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LpHedgeGatewayService.resolveOrderQty(
                        request().setQuantityDecimal("1.123456789").build()));

        assertEquals(true, e.getMessage().startsWith("INVALID_QUANTITY"));
    }

    @Test
    void quantityWithinMaxQuantityIsAccepted() {
        BigDecimal qty = LpHedgeGatewayService.resolveOrderQty(
                request().setQuantityDecimal("3.21").setMaxQuantity("5").build());

        assertEquals(new BigDecimal("3.21"), qty);
    }

    @Test
    void quantityAboveMaxQuantityIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LpHedgeGatewayService.resolveOrderQty(
                        request().setQuantityDecimal("6").setMaxQuantity("5").build()));

        assertEquals(true, e.getMessage().startsWith("QUANTITY_ABOVE_MAX"));
    }

    @Test
    void invalidMaxQuantityIsRejectedAsInvalidQuantity() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LpHedgeGatewayService.resolveOrderQty(
                        request().setQuantityDecimal("3.21").setMaxQuantity("not-a-number").build()));

        assertEquals(true, e.getMessage().startsWith("INVALID_QUANTITY"));
    }

    @Test
    void emptyMaxQuantityMeansNoCap() {
        BigDecimal qty = LpHedgeGatewayService.resolveOrderQty(
                request().setQuantityDecimal("1000000").setMaxQuantity("").build());

        assertEquals(new BigDecimal("1000000"), qty);
    }
}
