package com.aurify.fixclient;

import com.aurify.fixclient.grpc.LpHedgeGatewayService;
import com.aurify.fixclient.provider.ProviderAdapterRegistry;
import com.aurify.fixclient.provider.finalto.FinaltoStartupWorkflow;
import com.aurify.fixclient.provider.fxcubic.FxCubicStartupWorkflow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Boots the whole application context - the one thing every other test in this
 * module deliberately avoids, and therefore the one class of failure none of
 * them can catch.
 *
 * This exists because of a real outage: giving the provider startup workflows a
 * constructor dependency on OutboundFixDispatchService closed a bean cycle
 * (dispatch -> ProviderAdapterRegistry -> every adapter -> its startup
 * workflow -> dispatch). Every unit test passed, because each one wires its
 * collaborators by hand; Spring refused to start and the deployed gateway
 * crash-looped. A context that refuses to refresh must fail here, not on the
 * server.
 *
 * Ports are set to 0 so the HTTP and gRPC listeners take ephemeral ones and two
 * runs can overlap. The gateway boots with zero FIX sessions and no LP
 * credentials, so nothing here dials a venue.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "fix-gateway.grpc.port=0",
                "fix-gateway.session.file-store-path=target/test-fix/store",
                "fix-gateway.session.file-log-path=target/test-fix/log"
        })
class ApplicationContextLoadsTest {

    @Autowired
    private ProviderAdapterRegistry providerAdapterRegistry;

    @Autowired
    private LpHedgeGatewayService lpHedgeGatewayService;

    @Autowired
    private FxCubicStartupWorkflow fxCubicStartupWorkflow;

    @Autowired
    private FinaltoStartupWorkflow finaltoStartupWorkflow;

    @Test
    void theContextRefreshesWithEveryProviderAndTheQuotePipelineWiredIn() {
        assertNotNull(providerAdapterRegistry);
        assertNotNull(lpHedgeGatewayService);
        // Named explicitly: these two are the beans that closed the cycle.
        assertNotNull(fxCubicStartupWorkflow);
        assertNotNull(finaltoStartupWorkflow);
    }
}
