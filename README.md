# FIX Gateway

A provider-neutral FIX execution service. It owns FIX protocol mechanics and
nothing else: **it holds no liquidity-provider configuration of its own.**

Read **FIX-GATEWAY-ARCHITECTURE.md** for the full architecture and message
flows, and **GRPC-LP-HEDGE.md** for the caller-facing contract.

## The core idea

Every gRPC request carries an `LpSessionSpec` — host, port, comp IDs,
credentials, symbol allowlist and risk limits for one LP account. The caller
(the Node backend) is the source of truth for all of it; this gateway just
connects, sends, and reports back.

That is what makes the service independent:

- adding an LP is a database change upstream, never a redeploy here
- editing an LP's credentials rebuilds its session automatically (the spec
  fingerprint changes), with no restart
- the same binary can serve any caller, against any LP

Sessions are established lazily: the first order for an account pays the logon
cost (~2-3s), later orders reuse the live session (~50ms), and a session nobody
has traded on for `fix-gateway.session.idle-timeout-minutes` is logged out.
Sequence numbers survive in `data/store/`, so a rebuilt session resumes rather
than resetting.

## What's here

Under `src/main/java/com/aurify/fixclient/`:

- `transport/` — thin `GatewayFixApplication`, `GatewayMessageCracker`
- `session/` — `DynamicSessionManager`, `LpSessionRegistry`, `IdleSessionReaper`,
  `LpSessionSpec`, `DirectSessionControlService`
- `canonical/` — provider-agnostic DTOs and enums
- `provider/` — `LiquidityProviderAdapter` interface, `AbstractQuickFixProviderAdapter`,
  `ProviderAdapterRegistry`, `OutboundPolicy`, and two concrete adapters:
  `provider/fxcubic/` (FXCubic FIX API v1.2.2, FIX 4.3) and `provider/finalto/`
  (Finalto/CFH FIX API v2.6, FIX 4.4). Adding a provider is five files plus
  `@Component`; the registry indexes every adapter bean by `providerName()`,
  so nothing else in the codebase learns the name. See **Adding a provider**.
- `pipeline/` — Reactor-based bounded inbound/outbound queues + pipelines
- `dispatch/` — outbound FIX dispatch (validate → build → send)
- `grpc/` — `LpHedgeGatewayService`: `SubmitMarketOrder`, `EnsureSession`,
  `GetSessionStatus`, `CloseSession`
- `events/`, `persistence/`, `health/`, `admin/`

## Adding a provider

Copy the shape of `provider/finalto/`: five classes — `…ProviderAdapter`
(`@Component`, declares `providerName()` and `capabilities()`),
`…IncomingMapper`, `…OutgoingBuilder`, `…StartupWorkflow`, `…SymbolNormalizer`.
Nothing outside that package changes here; upstream, add the name to the
caller's provider enum so an operator can select it.

**Write the mapper's `ExecType`/`OrdStatus` switches from that venue's own
spec, never by copying another provider's.** The two we have disagree: FXCubic
sends a fill as `ExecType=2`, Finalto as `ExecType=F` (Trade) and never `2` at
all. Finalto's mapper was originally copied from FXCubic's and mapped only
`2`, so every real fill arrived as `UNKNOWN`.

`OrdStatus` is the more dangerous half, because `PendingOrderRegistry`
completes a waiting hedge call only on FILLED / REJECTED / CANCELLED. A status
left unmapped is `UNKNOWN`, which is not terminal — so the venue answers, the
hedge is real, and the caller still times out with
`FIX_EXECUTION_REPORT_TIMEOUT`. With TimeInForce=IOC the normal close of a
partially filled order is `4` (Cancelled), not a fill, so a mapper missing `1`
and `4` breaks on the first thin market rather than in testing. In-flight
states (`6` Pending Cancel, `E` Pending Replace) are left unmapped on purpose.

Fork a data dictionary only if the venue's custom tags are below 5000 (FXCubic
tag 583 → `FIX43-fxcubic.xml`). Tags at 5000+ are user-defined, so overriding
`validateUserDefinedFields()` to `false` is enough — that is why Finalto uses
the stock `FIX44.xml` from `quickfixj-messages-fix44` unchanged.

## Security

- **The gRPC port carries LP FIX passwords.** Enable mTLS
  (`fix-gateway.grpc.tls.*`) in every environment that reaches a real LP.
  Plaintext is for loopback development only, and the gateway logs a warning
  when it starts that way.
- **Set a shared secret too** (`fix-gateway.grpc.auth.token`, env
  `LP_HEDGE_GRPC_AUTH_TOKEN`, matching the caller's). The caller has always sent
  `authorization: Bearer <token>`; `AuthTokenServerInterceptor` is what checks
  it, closing an unauthenticated call with `UNAUTHENTICATED` before the service
  sees it. Leave it blank and the check is off — the gateway warns at startup;
  set `fix-gateway.grpc.auth.required: true` (env `LP_HEDGE_GRPC_AUTH_REQUIRED`)
  outside loopback so a missing token fails the boot instead of the audit. mTLS
  proves the caller holds a trusted certificate, the token proves it is *this*
  backend, and the token is the only control left if TLS terminates elsewhere.
- Credentials are redacted in `LpSessionSpec.toString()`, `SessionMetadata`, and
  the outbound Logon log line. QuickFIX's own `FileLogFactory` still writes raw
  FIX to `data/log/` — restrict access to that directory.
- Nothing in `data/` or any `*.pem`/`*.key` is committed; see `.gitignore`.

## Conformance status

Both adapters have been run against their venue's demo endpoint through the
real path (Node -> gRPC -> `DynamicSessionManager` -> adapter -> FIX), not a
standalone harness.

**Finalto (CFH) demo, 2026-09-07** - every row of their Order-session
conformance sheet that `SubmitMarketOrder` can express:

| Scenario | Result |
|---|---|
| Logon / Logout | OK |
| BUY 1k EURUSD Market FoK | FILLED, 1 report |
| BUY 1k EURUSD Market IoC | FILLED, 1 report |
| SELL 10,000,000 Market IoC | `NEW` -> `PARTIALLY_FILLED` (6M) -> `FILLED` (4M), 3 reports |
| SELL 50 EURUSD Market FoK | REJECTED, `OrderMinSizeExceeded` |

The Limit-order rows are not covered: `SubmitMarketOrder` sends market orders
only. FXCubic was re-run alongside as a regression and still fills.

Three things that only a live run surfaced:

- **`PartyID` (448) is genuinely required.** Omitting the `NoPartyIDs` group
  gets `Required Field missing (448)`. The value is ours to choose - it is
  `PartyIDSource=D` (proprietary) - and `AURIFY` was accepted as-is; CFH issues
  nothing. Keep the caller-side validation that demands it for this provider.
- **TransactTime must be UTC**, see below.
- **Finalto's demo drops roughly every other reconnect** for a comp ID pair
  that recently logged out: the Logon we send is byte-identical on the attempt
  that fails and the one that succeeds, and the failing one gets no reply at
  all. `ensureSession` now rebuilds the session and tries again
  (`fix-gateway.session.logon-attempts`, default 2) rather than surfacing the
  timeout - a lost first logon used to fail the hedge, and upstream that rolls
  back the whole metal transaction. The attempts **share**
  `logon-timeout-seconds` rather than each getting it, so the worst case is
  unchanged and still fits inside the caller's RPC deadline; a logon that will
  succeed takes about a second, so a spent attempt is dead time, not slowness.

## TransactTime is UTC, and only one venue says so

`LocalDateTime.now()` reads the JVM's default zone. Built that way, tag 60 went
out in local time while QuickFIX's own SendingTime (52) was correct UTC - from
an IST host, 5h30m apart in the same message. FXCubic accepts it; Finalto
rejects every order with `Invalid TransactTime`.

Both builders now use `LocalDateTime.now(ZoneOffset.UTC)` and both have a test
pinning it. A gateway deployed on a UTC host would never have shown this, so do
not treat "it works in staging" as evidence here.

## Diagnosing a session that logs on and then dies

A logon can succeed and the session still be dead half a minute later. FXCubic
sets `ResetSeqNumFlag=Y` on its Logon; if the LP account is configured with
`resetSeqNumOnLogon: false`, the two sides disagree about sequence numbers, the
LP sends a `ResendRequest` that cannot be satisfied, and it disconnects. Orders
sent in that window get no ExecutionReport, which reaches the caller as a
timeout with nothing FIX-shaped to point at.

Tell-tale signs:

- `GET /admin/sessions` shows `nextInboundSeqNum` and `nextOutboundSeqNum`
  drifting apart, and `state` flipping to `CONNECTING` on its own
- `data/log/<session>.event.log` contains `Received ResendRequest FROM: 1 TO:
  infinity` followed by `Disconnecting: Encountered END_OF_STREAM`

The fix is `resetSeqNumOnLogon: true` on that LP account.

## One session per comp ID pair

An LP allows one live session per SenderCompID/TargetCompID. A second
connection with the same pair is dropped at the TCP level with no FIX-level
explanation - `Encountered END_OF_STREAM` in `data/log/*.event.log`. The
gateway checks for this and refuses with `LP_SESSION_COMP_ID_CONFLICT` rather
than waiting out a logon timeout, but the real fix is to give each LP account
its own credentials.

If a logon that used to work starts timing out, check `GET /admin/sessions`
first: something else is probably still holding that LP's slot.

## Known gaps

- Market data is out of scope: only trading sessions are established, and
  `FxCubicStartupWorkflow` is a deliberate no-op. Re-adding it means taking the
  symbols from the caller's spec and subscribing on a PRICING session only.
- `PendingOrderRegistry` is process-local, so **run a single instance** until it
  is backed by shared durable storage.
- JDBC `PersistenceGateway` is not implemented (JPA entities are scaffolded and
  auto-configuration is excluded in `application.yml`).
- PrimeXM adapter is not built. Mirror the `provider/fxcubic/` package exactly
  once you have their spec — and never add `if (provider.equals(...))` branching
  outside an adapter.
- `pom.xml` `groupId` is still `com.yourorg`; older docs refer to the pre-rename
  package `com.yourorg.fixgateway`. The code is `com.aurify.fixclient`.

## Build and run

```
mvn test              # 34 tests, including a real QuickFIX acceptor in-process
mvn spring-boot:run   # HTTP 8080, gRPC 9090
```

No credentials or LP hosts are needed to start — the gateway boots with zero
sessions and reports healthy. Establish a session with the `EnsureSession` RPC,
or over HTTP with `POST /admin/sessions` (same body, for curl and Postman).

A Postman collection covering both this gateway and the Node side is at
`../docs/postman/Aurify-LP-FIX-Gateway.postman_collection.json`.

The standalone connectivity check reads its credentials from the environment:

```
export FIX_TEST_SENDER_COMP_ID=... FIX_TEST_TARGET_COMP_ID=... \
       FIX_TEST_HOST=... FIX_TEST_PORT=... \
       FIX_TEST_USERNAME=... FIX_TEST_PASSWORD=...
mvn compile exec:java -Dexec.mainClass=com.aurify.fixclient.manualtest.ManualFirstOrderTest
```
