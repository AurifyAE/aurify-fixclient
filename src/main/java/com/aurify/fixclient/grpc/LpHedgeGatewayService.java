package com.aurify.fixclient.grpc;

import aurify.lphedge.v1.CloseSessionRequest;
import aurify.lphedge.v1.EnsureSessionRequest;
import aurify.lphedge.v1.LpHedgeGatewayGrpc;
import aurify.lphedge.v1.SessionStatusRequest;
import aurify.lphedge.v1.SessionStatusResponse;
import aurify.lphedge.v1.SubmitMarketOrderRequest;
import aurify.lphedge.v1.SubmitMarketOrderResponse;
import com.aurify.fixclient.canonical.enums.CanonicalOrdStatus;
import com.aurify.fixclient.canonical.enums.CanonicalSide;
import com.aurify.fixclient.canonical.event.CanonicalExecutionReport;
import com.aurify.fixclient.canonical.event.CanonicalOrderRequest;
import com.aurify.fixclient.dispatch.OutboundFixDispatchService;
import com.aurify.fixclient.session.DynamicSessionManager;
import com.aurify.fixclient.session.LpSessionEntry;
import com.aurify.fixclient.session.LpSessionException;
import com.aurify.fixclient.session.LpSessionSpec;
import com.aurify.fixclient.session.SessionRole;
import com.aurify.fixclient.session.SessionState;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * The gateway's only inbound contract.
 *
 * Every request carries its own LpSessionSpec: the gateway holds no LP
 * configuration, so the caller stays the source of truth and this service can
 * be pointed at any LP without a redeploy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LpHedgeGatewayService extends LpHedgeGatewayGrpc.LpHedgeGatewayImplBase {

    /** Headroom kept back from the caller's deadline so our answer wins the race. */
    private static final long RESPONSE_MARGIN_MS = 500L;

    private final OutboundFixDispatchService dispatchService;
    private final PendingOrderRegistry pendingOrders;
    private final GrpcGatewayProperties properties;
    private final DynamicSessionManager sessionManager;

    @Override
    public void submitMarketOrder(SubmitMarketOrderRequest request,
                                  StreamObserver<SubmitMarketOrderResponse> observer) {
        try {
            validate(request);
            LpSessionSpec spec = LpSessionSpecMapper.toDomain(request.getSession());
            assertMatchingAccount(request, spec);

            // Slow path on the first order for this account: logon happens here.
            LpSessionEntry session = sessionManager.ensureSession(spec, SessionRole.TRADING);
            session.touch();

            String clOrdId = clOrdId(request);
            PendingOrderRegistry.Registration registration =
                    pendingOrders.register(request.getIdempotencyKey(), clOrdId);

            if (registration.isOwner()) {
                try {
                    dispatchService.dispatchOrThrow(
                            toCanonicalOrder(request, spec, clOrdId),
                            spec.policy(),
                            session.sessionId(),
                            spec.lpAccountId());
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
                        if (error != null) {
                            observer.onNext(toFailureResponse(
                                    error, clOrdId, timeoutMs, registration.getLastAck()));
                            observer.onCompleted();
                        } else {
                            observer.onNext(toResponse(report));
                            observer.onCompleted();
                        }
                    });
        } catch (LpSessionException e) {
            log.warn("Session unavailable for order {}: {}", request.getOrderId(), e.getMessage());
            observer.onNext(failure(e.getCode(), e.getMessage()));
            observer.onCompleted();
        } catch (IllegalArgumentException e) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (Exception e) {
            observer.onError(Status.INTERNAL.withDescription("Unable to submit market order")
                    .withCause(e).asRuntimeException());
        }
    }

    /** Connectivity check: logs on without sending an order. */
    @Override
    public void ensureSession(EnsureSessionRequest request, StreamObserver<SessionStatusResponse> observer) {
        try {
            LpSessionSpec spec = LpSessionSpecMapper.toDomain(request.getSession());
            LpSessionEntry entry = sessionManager.ensureSession(spec, SessionRole.TRADING);
            observer.onNext(sessionStatus(entry, spec.lpAccountId()));
            observer.onCompleted();
        } catch (LpSessionException e) {
            observer.onNext(sessionFailure(request.getSession().getLpAccountId(), e.getCode(), e.getMessage()));
            observer.onCompleted();
        } catch (IllegalArgumentException e) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getSessionStatus(SessionStatusRequest request, StreamObserver<SessionStatusResponse> observer) {
        LpSessionEntry entry = sessionManager.statusOf(request.getLpAccountId(), SessionRole.TRADING);
        observer.onNext(entry != null
                ? sessionStatus(entry, request.getLpAccountId())
                : SessionStatusResponse.newBuilder()
                        .setSuccess(true)
                        .setLpAccountId(request.getLpAccountId())
                        .setState(SessionState.ABSENT.name())
                        .build());
        observer.onCompleted();
    }

    @Override
    public void closeSession(CloseSessionRequest request, StreamObserver<SessionStatusResponse> observer) {
        boolean closed = sessionManager.closeSession(request.getLpAccountId(), SessionRole.TRADING);
        observer.onNext(SessionStatusResponse.newBuilder()
                .setSuccess(closed)
                .setLpAccountId(request.getLpAccountId())
                .setState(SessionState.ABSENT.name())
                .build());
        observer.onCompleted();
    }

    private CanonicalOrderRequest toCanonicalOrder(SubmitMarketOrderRequest request,
                                                   LpSessionSpec spec, String clOrdId) {
        return CanonicalOrderRequest.builder()
                .provider(spec.provider())
                .clOrdId(clOrdId)
                // Tag 1 must be the account at the LP; our lp_account_id means
                // nothing to the venue and gets the order rejected.
                .account(spec.accountOrNull())
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

    /**
     * How long to wait for the ExecutionReport.
     *
     * Deliberately finishes before the caller's deadline: matching it exactly
     * means the caller times out first and sees a bare DEADLINE_EXCEEDED
     * instead of the reason the gateway would have reported.
     */
    long timeoutMs() {
        if (Context.current().getDeadline() == null) return properties.getOrderTimeoutMs();
        long remaining = TimeUnit.NANOSECONDS.toMillis(
                Context.current().getDeadline().timeRemaining(TimeUnit.NANOSECONDS));
        return Math.max(1, Math.min(properties.getOrderTimeoutMs(), remaining - RESPONSE_MARGIN_MS));
    }

    private void validate(SubmitMarketOrderRequest request) {
        required(request.getIdempotencyKey(), "idempotency_key");
        required(request.getOrderId(), "order_id");
        required(request.getLpAccountId(), "lp_account_id");
        required(request.getSymbol(), "symbol");
        if (!request.hasSession()) throw new IllegalArgumentException("session is required");
        if (request.getQuantity() <= 0) throw new IllegalArgumentException("quantity must be greater than zero");
        String side = request.getSide().toUpperCase(Locale.ROOT);
        if (!side.equals("BUY") && !side.equals("SELL")) throw new IllegalArgumentException("side must be BUY or SELL");
        if (!request.getOrderType().isBlank() && !request.getOrderType().equalsIgnoreCase("MARKET"))
            throw new IllegalArgumentException("only MARKET orders are supported");
    }

    /** Guards against an order being routed down another account's session. */
    private void assertMatchingAccount(SubmitMarketOrderRequest request, LpSessionSpec spec) {
        if (!request.getLpAccountId().equals(spec.lpAccountId())) {
            throw new IllegalArgumentException(
                    "lp_account_id does not match session.lp_account_id");
        }
    }

    private void required(String value, String field) { if (blankToNull(value) == null) throw new IllegalArgumentException(field + " is required"); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    private SessionStatusResponse sessionStatus(LpSessionEntry entry, String lpAccountId) {
        return SessionStatusResponse.newBuilder()
                .setSuccess(entry.state() == SessionState.LOGGED_ON)
                .setLpAccountId(lpAccountId)
                .setState(entry.state().name())
                .setSessionId(entry.sessionId().toString())
                .setSpecFingerprint(entry.specFingerprint())
                .setLoggedOnAtEpochMs(entry.loggedOnAtEpochMs())
                .setLastUsedAtEpochMs(entry.lastUsedAtEpochMs())
                .build();
    }

    private SessionStatusResponse sessionFailure(String lpAccountId, String code, String message) {
        return SessionStatusResponse.newBuilder()
                .setSuccess(false)
                .setLpAccountId(nullToEmpty(lpAccountId))
                .setState(SessionState.FAILED.name())
                .setErrorCode(code)
                .setErrorMessage(nullToEmpty(message))
                .build();
    }

    private SubmitMarketOrderResponse toResponse(CanonicalExecutionReport report) {
        boolean success = report.getOrdStatus() != CanonicalOrdStatus.REJECTED;
        return SubmitMarketOrderResponse.newBuilder().setSuccess(success).setClOrdId(report.getClOrdId())
                .setOrderId(nullToEmpty(report.getOrderId())).setStatus(report.getOrdStatus().name())
                .setAvgPx(decimal(report.getAvgPx())).setCumQty(decimal(report.getCumQty()))
                .setLeavesQty(decimal(report.getLeavesQty())).setTerminal(isTerminal(report.getOrdStatus()))
                .setErrorCode(success ? "" : "FIX_ORDER_REJECTED")
                .setErrorMessage(success ? "" : nullToEmpty(report.getRejectText())).build();
    }

    /** An LP reject is a definite answer, not a timeout - report it as such. */
    private SubmitMarketOrderResponse toFailureResponse(Throwable error, String clOrdId,
                                                        long timeoutMs, CanonicalExecutionReport lastAck) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;

        if (cause instanceof PendingOrderRegistry.OrderRejectedException rejected) {
            return SubmitMarketOrderResponse.newBuilder()
                    .setSuccess(false)
                    .setClOrdId(nullToEmpty(clOrdId))
                    .setStatus(CanonicalOrdStatus.REJECTED.name())
                    .setTerminal(true)
                    .setErrorCode("FIX_ORDER_REJECTED")
                    .setErrorMessage(nullToEmpty(rejected.getMessage()))
                    .build();
        }

        // The LP acknowledged but never settled the order. Report the ack and
        // its status, flagged non-terminal: the caller must not book this as a
        // completed hedge, and must not assume it failed either.
        if (lastAck != null) {
            return SubmitMarketOrderResponse.newBuilder()
                    .setSuccess(false)
                    .setClOrdId(nullToEmpty(lastAck.getClOrdId()))
                    .setOrderId(nullToEmpty(lastAck.getOrderId()))
                    .setStatus(lastAck.getOrdStatus().name())
                    .setTerminal(false)
                    .setErrorCode("FIX_ORDER_NOT_TERMINAL")
                    .setErrorMessage("Order was acknowledged as " + lastAck.getOrdStatus()
                            + " but no final execution report arrived within " + timeoutMs
                            + " ms - its outcome at the LP is unknown")
                    .build();
        }

        return SubmitMarketOrderResponse.newBuilder()
                .setSuccess(false)
                .setClOrdId(nullToEmpty(clOrdId))
                .setTerminal(false)
                .setErrorCode("FIX_EXECUTION_REPORT_TIMEOUT")
                .setErrorMessage("No FIX execution report received within " + timeoutMs + " ms")
                .build();
    }

    private SubmitMarketOrderResponse failure(String code, String message) { return SubmitMarketOrderResponse.newBuilder().setSuccess(false).setErrorCode(code).setErrorMessage(nullToEmpty(message)).build(); }
    private double decimal(BigDecimal value) { return value == null ? 0D : value.doubleValue(); }
    private String nullToEmpty(String value) { return value == null ? "" : value; }
    private boolean isTerminal(CanonicalOrdStatus status) { return status == CanonicalOrdStatus.FILLED || status == CanonicalOrdStatus.REJECTED || status == CanonicalOrdStatus.CANCELLED; }
}
