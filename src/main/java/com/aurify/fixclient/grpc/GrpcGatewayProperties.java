package com.aurify.fixclient.grpc;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "fix-gateway.grpc")
public class GrpcGatewayProperties {
    private int port = 9090;
    private long orderTimeoutMs = 10_000;
    private Tls tls = new Tls();

    /**
     * Transport security for the gRPC port.
     *
     * This channel carries LP FIX passwords, so it must be mTLS in any
     * environment that touches a real LP. Plaintext is allowed only when the
     * gateway is bound to loopback for local development.
     */
    @Getter
    @Setter
    public static class Tls {
        private boolean enabled = false;
        /** PEM certificate chain presented by the gateway. */
        private String certChainPath;
        /** PKCS#8 private key for the above chain. */
        private String privateKeyPath;
        /** CA bundle used to verify client certificates. Required for mTLS. */
        private String trustCertCollectionPath;
        /** When true, a client without a valid certificate is refused. */
        private boolean requireClientAuth = true;
    }
}
