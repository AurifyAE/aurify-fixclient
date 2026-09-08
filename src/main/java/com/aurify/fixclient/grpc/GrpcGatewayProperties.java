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
    private Auth auth = new Auth();

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

    /**
     * Shared-secret check on top of the transport.
     *
     * <p>The caller already sends {@code authorization: Bearer <token>} on every
     * request; without a token configured here the gateway simply ignored it,
     * leaving mTLS as the only thing standing between the internet and a port
     * that accepts LP credentials.
     */
    @Getter
    @Setter
    public static class Auth {
        /** Shared secret the caller must present. Blank disables the check. */
        private String token;
        /**
         * Refuse to start without a token. Left off so a loopback development
         * run still works, but any deployment that is not loopback should set
         * it - the gateway warns loudly when both this and the token are off.
         */
        private boolean required = false;
    }
}
