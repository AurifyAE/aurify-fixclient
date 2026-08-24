# Node.js → gRPC → FIX flow

The gateway listens on `0.0.0.0:9090` by default. It accepts the supplied
`aurify.lphedge.v1.LpHedgeGateway/SubmitMarketOrder` unary RPC.

1. The service validates the order and uses `correlation_id` (or `order_id`) as
   the FIX `ClOrdID`.
2. It constructs a provider-neutral market `CanonicalOrderRequest` and sends it
   through the configured provider adapter (`fxcubic` by default).
3. QuickFIX/J receives an `ExecutionReport`; the existing inbound pipeline maps
   it to `CanonicalExecutionReport`.
4. The gRPC call completes with that execution report. The first report is an
   acknowledgement (NEW, FILL, or REJECTED); it is not a subscription to later
   fills.

`lp_api_key` is deliberately not put into the FIX message or logged. Authenticate
the Node caller with mTLS or a gRPC interceptor and configure LP credentials on
the Java gateway. Do not trust per-order credentials supplied by callers.

## Start

```bash
mvn spring-boot:run
```

Configure the port, provider, and maximum wait for a FIX execution report:

```yaml
fix-gateway:
  grpc:
    port: 9090
    provider: fxcubic
    order-timeout-ms: 10000
```

## Node client

Your current `submitMarketOrder` client can use `@grpc/grpc-js` and
`@grpc/proto-loader` against `src/main/proto/lp_hedge_gateway.proto`.

```js
const client = new LpHedgeGatewayClient(
  'fix-gateway.internal:9090',
  grpc.credentials.createSsl(caCertificate), // use mTLS in production
);

client.submitMarketOrder(request, { deadline: new Date(Date.now() + 10_000) }, callback);
```

For the proto-loader configuration, set `keepCase: false` so the Java-generated
schema receives the camel-case request object your Node code already sends.

## Production boundary

The in-memory correlation/idempotency registry is intentionally process-local.
Before running more than one gateway instance, replace it with durable shared
storage and ensure the FIX `ClOrdID` is globally unique. Also add gRPC mTLS (or
an authentication interceptor), authorization by account, and audit-safe secret
handling.
