package com.aurify.fixclient.grpc;

import aurify.lphedge.v1.LpHedgeGatewayGrpc;
import aurify.lphedge.v1.SubmitMarketOrderRequest;
import aurify.lphedge.v1.SubmitMarketOrderResponse;
import com.aurify.fixclient.canonical.enums.CanonicalOrdStatus;
import com.aurify.fixclient.canonical.enums.CanonicalSide;
import com.aurify.fixclient.canonical.event.CanonicalExecutionReport;
import com.aurify.fixclient.canonical.event.CanonicalOrderRequest;
import com.aurify.fixclient.dispatch.OutboundFixDispatchService;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class LpHedgeGatewayService extends LpHedgeGatewayGrpc.LpHedgeGatewayImplBase {
    private final OutboundFixDispatchService dispatchService;
    private final PendingOrderRegistry pendingOrders;
    private final GrpcGatewayProperties properties;

    @Override
    public void submitMarketOrder(SubmitMarketOrderRequest request,
                                  StreamObserver<SubmitMarketOrderResponse> observer) {
        try {
            validate(request);
            String clOrdId = clOrdId(request);
            PendingOrderRegistry.Registration registration = pendingOrders.register(request.getIdempotencyKey(), clOrdId);

            if (registration.isOwner()) {
                try {
                    dispatchService.dispatchOrThrow(toCanonicalOrder(request, clOrdId));
                } catch (RuntimeException e) {
                    pendingOrders.discard(request.getIdempotencyKey(), clOrdId);
                    observer.onNext(failure("FIX_SEND_FAILED", e.getMessage()));
                    observer.onCompleted();
                    return;
                }
            }

            long timeoutMs = timeoutMs();
            registration.getFuture().orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .whenComplete((report, error) -> {
                        if (error != null) observer.onError(Status.DEADLINE_EXCEEDED
                                .withDescription("No FIX execution report received within " + timeoutMs + " ms")
                                .asRuntimeException());
                        else {
                            observer.onNext(toResponse(report));
                            observer.onCompleted();
                        }
                    });
        } catch (IllegalArgumentException e) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (Exception e) {
            observer.onError(Status.INTERNAL.withDescription("Unable to submit market order").withCause(e).asRuntimeException());
        }
    }

    private CanonicalOrderRequest toCanonicalOrder(SubmitMarketOrderRequest request, String clOrdId) {
        return CanonicalOrderRequest.builder()
                .provider(properties.getProvider())
                .clOrdId(clOrdId)
                .account(request.getLpAccountId())
                .ticketId(request.getOrderId())
                .group(blankToNull(request.getBranchId()) != null ? request.getBranchId() : request.getOrganizationId())
                .symbol(request.getSymbol())
                .side("SELL".equalsIgnoreCase(request.getSide()) ? CanonicalSide.SELL : CanonicalSide.BUY)
                .orderQty(BigDecimal.valueOf(request.getQuantity()))
                .ordType(CanonicalOrderRequest.OrdType.MARKET)
                .timeInForce(CanonicalOrderRequest.TimeInForce.IOC)
                .build();
    }

    /* A deterministic ID lets a retry match the original outbound FIX order. */
    private String clOrdId(SubmitMarketOrderRequest request) {
        return blankToNull(request.getCorrelationId()) != null ? request.getCorrelationId() : request.getOrderId();
    }

    private long timeoutMs() {
        if (Context.current().getDeadline() == null) return properties.getOrderTimeoutMs();
        return Math.max(1, Math.min(properties.getOrderTimeoutMs(),
                TimeUnit.NANOSECONDS.toMillis(Context.current().getDeadline().timeRemaining(TimeUnit.NANOSECONDS))));
    }

    private void validate(SubmitMarketOrderRequest request) {
        required(request.getIdempotencyKey(), "idempotency_key");
        required(request.getOrderId(), "order_id");
        required(request.getLpAccountId(), "lp_account_id");
        required(request.getSymbol(), "symbol");
        if (request.getQuantity() <= 0) throw new IllegalArgumentException("quantity must be greater than zero");
        String side = request.getSide().toUpperCase(Locale.ROOT);
        if (!side.equals("BUY") && !side.equals("SELL")) throw new IllegalArgumentException("side must be BUY or SELL");
        if (!request.getOrderType().isBlank() && !request.getOrderType().equalsIgnoreCase("MARKET"))
            throw new IllegalArgumentException("only MARKET orders are supported");
    }

    private void required(String value, String field) { if (blankToNull(value) == null) throw new IllegalArgumentException(field + " is required"); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    private SubmitMarketOrderResponse toResponse(CanonicalExecutionReport report) {
        boolean success = report.getOrdStatus() != CanonicalOrdStatus.REJECTED;
        return SubmitMarketOrderResponse.newBuilder().setSuccess(success).setClOrdId(report.getClOrdId())
                .setOrderId(nullToEmpty(report.getOrderId())).setStatus(report.getOrdStatus().name())
                .setAvgPx(decimal(report.getAvgPx())).setCumQty(decimal(report.getCumQty()))
                .setLeavesQty(decimal(report.getLeavesQty())).setTerminal(isTerminal(report.getOrdStatus()))
                .setErrorCode(success ? "" : "FIX_ORDER_REJECTED")
                .setErrorMessage(success ? "" : nullToEmpty(report.getRejectText())).build();
    }

    private SubmitMarketOrderResponse failure(String code, String message) { return SubmitMarketOrderResponse.newBuilder().setSuccess(false).setErrorCode(code).setErrorMessage(nullToEmpty(message)).build(); }
    private double decimal(BigDecimal value) { return value == null ? 0D : value.doubleValue(); }
    private String nullToEmpty(String value) { return value == null ? "" : value; }
    private boolean isTerminal(CanonicalOrdStatus status) { return status == CanonicalOrdStatus.FILLED || status == CanonicalOrdStatus.REJECTED || status == CanonicalOrdStatus.CANCELLED; }
}
