package com.aurify.fixclient.grpc;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

/** Runs gRPC alongside Spring's HTTP server, on a separate port. */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcServerLifecycle implements SmartLifecycle {
    private final LpHedgeGatewayService service;
    private final GrpcGatewayProperties properties;
    private volatile Server server;

    @Override
    public synchronized void start() {
        if (server != null) return;
        try {
            NettyServerBuilder builder = NettyServerBuilder.forPort(properties.getPort()).addService(service);
            applyTls(builder);
            server = builder.build().start();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start gRPC server on port " + properties.getPort(), e);
        }
    }

    /**
     * Requests on this port carry LP FIX credentials, so client certificates are
     * how the gateway knows the caller is the backend and not something else.
     */
    private void applyTls(NettyServerBuilder builder) {
        GrpcGatewayProperties.Tls tls = properties.getTls();
        if (!tls.isEnabled()) {
            log.warn("gRPC TLS is disabled - the LP hedge port is plaintext. "
                    + "Acceptable for local development only: bind it to loopback and never expose it.");
            return;
        }

        require(tls.getCertChainPath(), "fix-gateway.grpc.tls.cert-chain-path");
        require(tls.getPrivateKeyPath(), "fix-gateway.grpc.tls.private-key-path");

        SslContextBuilder ssl = SslContextBuilder.forServer(
                new File(tls.getCertChainPath()), new File(tls.getPrivateKeyPath()));

        if (tls.isRequireClientAuth()) {
            require(tls.getTrustCertCollectionPath(),
                    "fix-gateway.grpc.tls.trust-cert-collection-path (required when client auth is on)");
            ssl.trustManager(new File(tls.getTrustCertCollectionPath()))
               .clientAuth(ClientAuth.REQUIRE);
        }

        try {
            builder.sslContext(GrpcSslContexts.configure(ssl).build());
            log.info("gRPC server on port {} secured with TLS (client auth: {})",
                    properties.getPort(), tls.isRequireClientAuth() ? "required" : "off");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to configure gRPC TLS", e);
        }
    }

    private void require(String value, String settingName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(settingName + " must be set when gRPC TLS is enabled");
        }
    }

    @Override public synchronized void stop() { if (server != null) { server.shutdown(); server = null; } }
    @Override public boolean isRunning() { return server != null && !server.isShutdown(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }

    @PreDestroy
    void close() { stop(); }
}
