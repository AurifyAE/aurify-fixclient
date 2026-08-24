package com.yourorg.fixgateway.grpc;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Runs gRPC alongside Spring's HTTP server, on a separate port. */
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
            server = NettyServerBuilder.forPort(properties.getPort()).addService(service).build().start();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start gRPC server on port " + properties.getPort(), e);
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
