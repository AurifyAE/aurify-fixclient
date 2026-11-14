package com.aurify.fixclient.loadtest;

import aurify.lphedge.v1.FixSession;
import aurify.lphedge.v1.LpSessionSpec;
import aurify.lphedge.v1.SubmitMarketOrderRequest;
import aurify.lphedge.v1.SubmitMarketOrderResponse;
import com.aurify.fixclient.grpc.LpHedgeGatewayService;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real gateway against the fake LP, over a real FIX session: proves the
 * simulator speaks each provider's dialect well enough for the gateway to log
 * on, send an order, and settle it on the report that comes back. A message
 * either side's dictionary refused would show up here as a timeout.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "fix-gateway.grpc.port=0",
                "fix-gateway.session.file-store-path=target/test-fix/fake-lp/store",
                "fix-gateway.session.file-log-path=target/test-fix/fake-lp/log"
        })
class FakeLpGatewayTest {

    private static final FakeLpConfig BASE = new FakeLpConfig(0, FakeLpConfig.Profile.FXCUBIC, 5, 10, 0,
            100, 0, 0, 0, 0.6, new BigDecimal("2345.00"), new BigDecimal("0.25"), 7, 0, null, null, null);

    @Autowired
    private LpHedgeGatewayService gateway;

    private final List<FakeLp> running = new ArrayList<>();

    @AfterEach
    void stopLps() {
        running.forEach(FakeLp::stop);
    }

    @Test
    void anFxcubicMarketOrderFills() throws Exception {
        int port = start(BASE);

        SubmitMarketOrderResponse response = submit(port, "fxcubic", "FIX.4.3", order("5"));

        assertTrue(response.getSuccess(), response.getErrorMessage());
        assertEquals("FILLED", response.getStatus());
        assertEquals(5.0, response.getCumQty());
        assertEquals(2345.0, response.getAvgPx(), 1.0);
        // The ack and the fill, in arrival order.
        assertEquals(List.of("PENDING_NEW", "FILLED"),
                response.getReportsList().stream().map(report -> report.getOrdStatus()).toList());
    }

    @Test
    void anFxcubicRejectIsADefiniteAnswerNotATimeout() throws Exception {
        int port = start(BASE.withMix(0, 0, 0, 100));

        SubmitMarketOrderResponse response = submit(port, "fxcubic", "FIX.4.3", order("5"));

        assertFalse(response.getSuccess());
        assertEquals("REJECTED", response.getStatus());
        assertEquals("FIX_ORDER_REJECTED", response.getErrorCode());
    }

    @Test
    void aFinaltoPartialClosesAsCancelledWithWhatTraded() throws Exception {
        int port = start(BASE.withProfile(FakeLpConfig.Profile.FINALTO).withMix(0, 100, 0, 0));

        SubmitMarketOrderResponse response = submit(port, "finalto", "FIX.4.4", order("10"));

        assertTrue(response.getSuccess(), response.getErrorMessage());
        assertEquals("CANCELLED", response.getStatus());
        assertEquals(6.0, response.getCumQty());
        assertTrue(response.getTerminal());
    }

    @Test
    void aFinaltoLimitTheMarketNeverReachesTradesNothing() throws Exception {
        int port = start(BASE.withProfile(FakeLpConfig.Profile.FINALTO));

        SubmitMarketOrderResponse response = submit(port, "finalto", "FIX.4.4",
                order("10").setOrderType("LIMIT").setLimitPrice("1000.00"));

        assertTrue(response.getSuccess(), response.getErrorMessage());
        assertEquals("CANCELLED", response.getStatus());
        assertEquals(0.0, response.getCumQty());
    }

    @Test
    void aDecimalQuantityReachesTheLpUnrounded() throws Exception {
        int port = start(BASE.withProfile(FakeLpConfig.Profile.FINALTO));

        SubmitMarketOrderResponse response = submit(port, "finalto", "FIX.4.4",
                order("3").setQuantityDecimal("3.21"));

        assertEquals("FILLED", response.getStatus(), response.getErrorMessage());
        assertEquals(3.21, response.getCumQty(), 1e-9);
    }

    private int start(FakeLpConfig config) throws Exception {
        int port = freePort();
        running.add(FakeLp.start(config.withPort(port)));
        return port;
    }

    private static SubmitMarketOrderRequest.Builder order(String quantity) {
        String id = UUID.randomUUID().toString();
        return SubmitMarketOrderRequest.newBuilder()
                .setIdempotencyKey("lp-hedge:" + id)
                .setOrderId(id)
                .setSymbol("XAUUSD")
                .setSide("BUY")
                .setQuantity(Long.parseLong(quantity));
    }

    private SubmitMarketOrderResponse submit(int port, String provider, String fixVersion,
                                             SubmitMarketOrderRequest.Builder request) throws Exception {
        // A fresh account per test: each gets its own session, as each seeded LP account will.
        String account = "fake-lp-" + UUID.randomUUID();
        LpSessionSpec session = LpSessionSpec.newBuilder()
                .setLpAccountId(account)
                .setProvider(provider)
                .setFixVersion(fixVersion)
                .setSpecFingerprint(account)
                .setPartyId("FAKE-PARTY")
                .setTrading(FixSession.newBuilder()
                        .setHost("127.0.0.1").setPort(port)
                        .setSenderCompId("AURIFY_" + account.substring(8, 16)).setTargetCompId("FAKE_LP")
                        .setUsername("u").setPassword("p")
                        .setResetSeqNumOnLogon(true).setHeartbeatIntervalSeconds(30))
                .build();
        CompletableFuture<SubmitMarketOrderResponse> answer = new CompletableFuture<>();
        gateway.submitMarketOrder(request.setLpAccountId(account).setSession(session).build(),
                new StreamObserver<>() {
                    @Override
                    public void onNext(SubmitMarketOrderResponse value) {
                        answer.complete(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                        answer.completeExceptionally(t);
                    }

                    @Override
                    public void onCompleted() {
                    }
                });
        return answer.get(30, TimeUnit.SECONDS);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
