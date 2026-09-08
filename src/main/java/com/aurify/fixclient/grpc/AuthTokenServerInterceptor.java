package com.aurify.fixclient.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Rejects a gRPC call that does not carry the shared secret.
 *
 * <p>This port carries live LP FIX passwords inside every {@code LpSessionSpec},
 * so mTLS is the primary control. The token is the second one: it identifies
 * the caller as <em>this</em> backend rather than anything else holding a
 * certificate the gateway's CA happens to trust, and it is the only control at
 * all in a deployment that terminates TLS elsewhere.
 *
 * <p>The caller sends {@code authorization: Bearer <token>}; a bare token is
 * accepted too, since nothing about the scheme adds security here.
 */
@Slf4j
public class AuthTokenServerInterceptor implements ServerInterceptor {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final String BEARER_PREFIX = "bearer ";

    private final byte[] expected;

    public AuthTokenServerInterceptor(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("auth token must not be blank");
        }
        this.expected = token.trim().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <Q, S> ServerCall.Listener<Q> interceptCall(
            ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {

        String presented = stripBearer(headers.get(AUTHORIZATION));
        if (presented == null) {
            return deny(call, "missing authorization metadata", call.getMethodDescriptor().getFullMethodName());
        }
        if (!MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
            return deny(call, "invalid token", call.getMethodDescriptor().getFullMethodName());
        }
        return next.startCall(call, headers);
    }

    private static String stripBearer(String header) {
        if (header == null || header.isBlank()) return null;
        String value = header.trim();
        if (value.length() > BEARER_PREFIX.length()
                && value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            value = value.substring(BEARER_PREFIX.length()).trim();
        }
        return value.isEmpty() ? null : value;
    }

    /** The reason is logged, never returned: a caller guessing tokens learns nothing. */
    private <Q, S> ServerCall.Listener<Q> deny(ServerCall<Q, S> call, String reason, String method) {
        log.warn("Rejected gRPC call to {}: {}", method, reason);
        call.close(Status.UNAUTHENTICATED.withDescription("unauthenticated"), new Metadata());
        return new ServerCall.Listener<>() {};
    }
}
