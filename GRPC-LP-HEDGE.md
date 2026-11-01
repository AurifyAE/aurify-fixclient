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

## Quantity units

`quantity` (int64, tag 38 as a whole number) is the legacy field. Node now
prefers `quantity_decimal` - a decimal string in **venue units** (e.g. "3.21"
troy oz for `XAUUSD`) - whenever it is non-empty; units and rounding are
Node's decision, the gateway just carries the number through to FIX tag 38
unchanged. `resolveOrderQty` (`LpHedgeGatewayService`) rejects a non-numeric
value, a quantity that is not greater than zero, or a scale beyond 8 decimal
places with `INVALID_QUANTITY`.

`max_quantity` is an optional per-symbol venue-unit cap - a backstop to the
check Node already does before it ever calls the gateway, not the primary
enforcement. Left empty, there is no cap here; set, `quantity > max_quantity`
is rejected with `QUANTITY_ABOVE_MAX` before a session is even touched.

`LpSessionSpec.max_order_size` is unrelated: it is the account-level limit,
still expressed in grams on the Node side, so comparing it against a venue
quantity would compare two different units. Node sends it as `0` (disabled)
now that the per-order cap travels as `max_quantity`; `OutboundPolicy.
exceedsMaxOrderSize` still exists and still reads `max_order_size`, but now
takes the order quantity as a `BigDecimal` so a fractional venue quantity is
compared exactly rather than truncated to a `long` first. Both checks stay
provider-neutral - neither adapter branches on which provider a request is
for.

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
with mTLS anywhere but loopback, and set the shared secret on both sides:
`LP_HEDGE_GRPC_AUTH_TOKEN` on Node becomes the `authorization: Bearer <token>`
metadata on every request, and `fix-gateway.grpc.auth.token` is what the gateway
checks it against. A mismatch fails the call with `UNAUTHENTICATED`, which the
Node client surfaces as an error rather than retrying — unlike `UNAVAILABLE`.

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
storage and ensure the FIX `ClOrdID` is globally unique.

Transport security and caller authentication are in place (mTLS plus the shared
secret above); what is still missing is **authorization by account** — any
authenticated caller may trade any `lp_account_id` it supplies a spec for — and
audit-safe secret handling.
