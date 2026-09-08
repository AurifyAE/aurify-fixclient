package com.aurify.fixclient.grpc;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AuthTokenServerInterceptorTest {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final AuthTokenServerInterceptor interceptor = new AuthTokenServerInterceptor("s3cret");

    @Test
    void theTokenTheCallerActuallySendsIsAccepted() {
        // Node sends "Bearer <token>" - services/integrations/grpc/client.js.
        assertTrue(passes(header("Bearer s3cret")));
    }

    @Test
    void aBareTokenIsAcceptedToo() {
        assertTrue(passes(header("s3cret")));
    }

    @Test
    void theSchemeIsMatchedCaseInsensitively() {
        assertTrue(passes(header("bearer s3cret")));
    }

    @Test
    void aCallWithNoAuthorizationIsRefused() {
        assertEquals(Status.Code.UNAUTHENTICATED, statusOf(new Metadata()));
    }

    @Test
    void aWrongTokenIsRefused() {
        assertEquals(Status.Code.UNAUTHENTICATED, statusOf(header("Bearer wrong")));
    }

    @Test
    void aTokenThatIsOnlyAPrefixOfTheRealOneIsRefused() {
        assertEquals(Status.Code.UNAUTHENTICATED, statusOf(header("Bearer s3cre")));
    }

    @Test
    void aBearerHeaderWithNoTokenIsRefused() {
        assertEquals(Status.Code.UNAUTHENTICATED, statusOf(header("Bearer ")));
    }

    @Test
    void theRejectionSaysNothingAboutWhyItFailed() {
        FakeCall call = new FakeCall();
        interceptor.interceptCall(call, header("Bearer wrong"), failIfCalled());
        assertEquals("unauthenticated", call.status.get().getDescription());
    }

    @Test
    void aBlankTokenIsNotAValidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new AuthTokenServerInterceptor("  "));
        assertThrows(IllegalArgumentException.class, () -> new AuthTokenServerInterceptor(null));
    }

    private static Metadata header(String value) {
        Metadata metadata = new Metadata();
        metadata.put(AUTHORIZATION, value);
        return metadata;
    }

    private boolean passes(Metadata headers) {
        AtomicBoolean forwarded = new AtomicBoolean(false);
        FakeCall call = new FakeCall();
        interceptor.interceptCall(call, headers, (c, h) -> {
            forwarded.set(true);
            return new ServerCall.Listener<>() {};
        });
        assertNull(call.status.get(), "an accepted call must not be closed by the interceptor");
        return forwarded.get();
    }

    private Status.Code statusOf(Metadata headers) {
        FakeCall call = new FakeCall();
        interceptor.interceptCall(call, headers, failIfCalled());
        Status status = call.status.get();
        assertNotNull(status, "a refused call must be closed by the interceptor");
        return status.getCode();
    }

    private static ServerCallHandler<String, String> failIfCalled() {
        return (call, headers) -> fail("the service must not be reached by an unauthenticated call");
    }

    /** Captures the status the interceptor closes with; everything else is unused. */
    private static final class FakeCall extends ServerCall<String, String> {
        private final AtomicReference<Status> status = new AtomicReference<>();

        @Override public void request(int numMessages) { }
        @Override public void sendHeaders(Metadata headers) { }
        @Override public void sendMessage(String message) { }
        @Override public void close(Status status, Metadata trailers) { this.status.set(status); }
        @Override public boolean isCancelled() { return false; }

        @Override
        public MethodDescriptor<String, String> getMethodDescriptor() {
            return MethodDescriptor.<String, String>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("aurify.lphedge.v1.LpHedgeGateway/SubmitMarketOrder")
                    .setRequestMarshaller(PASSTHROUGH)
                    .setResponseMarshaller(PASSTHROUGH)
                    .build();
        }

        private static final MethodDescriptor.Marshaller<String> PASSTHROUGH =
                new MethodDescriptor.Marshaller<>() {
                    @Override public InputStream stream(String value) { return InputStream.nullInputStream(); }
                    @Override public String parse(InputStream stream) { return ""; }
                };
    }
}
