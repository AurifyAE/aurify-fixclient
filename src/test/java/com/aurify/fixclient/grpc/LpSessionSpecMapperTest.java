package com.aurify.fixclient.grpc;

import aurify.lphedge.v1.FixSession;
import com.aurify.fixclient.session.LpSessionSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LpSessionSpecMapperTest {

    private static aurify.lphedge.v1.LpSessionSpec.Builder stonex() {
        FixSession order = FixSession.newBuilder()
                .setHost("fx.example.com").setPort(13976)
                .setSenderCompId("order.APITESTINTM.082").setTargetCompId("demo.fxgrid")
                .setUsername("API82").setPassword("secret")
                .setSenderSubId("APITESTINTM")
                .build();
        FixSession quote = order.toBuilder().setPort(13975).setSenderCompId("quote.APITESTINTM.082").build();
        return aurify.lphedge.v1.LpSessionSpec.newBuilder()
                .setLpAccountId("acct-stonex").setProvider("stonex").setFixVersion("FIX.4.3")
                .setTrading(order).setPricing(quote)
                .addAllowedSymbols("XAUUSD")
                .setDeliverToCompId("BRKD");
    }

    @Test
    void stonexFieldsReachTheDomainSpec() {
        LpSessionSpec spec = LpSessionSpecMapper.toDomain(stonex().build());

        assertEquals("APITESTINTM", spec.trading().senderSubId());
        assertEquals("APITESTINTM", spec.pricing().senderSubId());
        assertEquals("BRKD", spec.deliverToCompIdOrNull());
    }

    @Test
    void venuesWithoutThemGetEmptyValues() {
        aurify.lphedge.v1.LpSessionSpec proto = stonex()
                .clearDeliverToCompId()
                .setTrading(stonex().getTrading().toBuilder().clearSenderSubId())
                .build();

        LpSessionSpec spec = LpSessionSpecMapper.toDomain(proto);

        assertEquals("", spec.trading().senderSubId());
        assertNull(spec.deliverToCompIdOrNull());
    }

    /** With no caller fingerprint, editing either field must still relog on. */
    @Test
    void derivedFingerprintCoversSenderSubIdAndDeliverTo() {
        String base = LpSessionSpecMapper.fingerprintOf(stonex().build());

        String otherSubId = LpSessionSpecMapper.fingerprintOf(stonex()
                .setTrading(stonex().getTrading().toBuilder().setSenderSubId("OTHER")).build());
        String otherDeliverTo = LpSessionSpecMapper.fingerprintOf(stonex().setDeliverToCompId("OTHER").build());

        assertNotEquals(base, otherSubId);
        assertNotEquals(base, otherDeliverTo);
    }
}
