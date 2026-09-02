# Node.js → gRPC → FIX flow

The gateway listens on `0.0.0.0:9090` by default. It accepts the supplied
`aurify.lphedge.v1.LpHedgeGateway/SubmitMarketOrder` unary RPC.

1. The service validates the order and uses `correlation_id` (or `order_id`) as
   the FIX `ClOrdID`.
2. It constructs a provider-neutral market `CanonicalOrderRequest` and sends it
   through the configured provider adapter (`fxcubic` by default).
3. QuickFIX/J receives an `ExecutionReport`; the existing inbound pipeline maps
   it to `CanonicalExecutionReport` and the `ExecutionJournal` records it.
4. The gRPC call completes on the first **terminal** report (FILLED, REJECTED or
   CANCELLED). It does not complete on the first report of any kind: FXCubic
   acknowledges with PENDING_NEW and decides milliseconds later, so answering on
   the ack would report a hedge the venue went on to reject.
5. The response carries `reports` — every report seen while the call was open,
   not only the one that ended it.

## Reports that arrive after the call

A venue keeps talking about an order after the RPC has answered: further partial
fills, or the eventual outcome of an order whose call timed out. Those have no
request to ride back on, so the caller subscribes to
`StreamExecutionReports` and records them itself. `since_epoch_ms` replays what
the journal still holds before going live, so a reconnect inside the retention
window loses nothing. `GetOrderExecutions` backfills one order on demand.

The journal (`fix-gateway.execution-journal.*`) is a bounded, non-durable replay
buffer, **not** a store — the durable execution ledger belongs to the caller.
Sizing it like a database would move business state back into the gateway, which
is exactly what this design keeps out.

LP credentials arrive per request inside `LpSessionSpec` and are injected into
the FIX Logon only — never into an application message, and never logged. The
`lp_api_key` field is removed from the proto (its field number is reserved).
Because this channel carries live venue passwords, authenticate the Node caller
with mTLS anywhere but loopback.

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
