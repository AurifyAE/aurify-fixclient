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
  `ProviderAdapterRegistry`, `OutboundPolicy`, and a concrete `provider/fxcubic/`
  adapter implementing every rule from the FXCubic FIX API v1.2.2 spec
- `pipeline/` — Reactor-based bounded inbound/outbound queues + pipelines
- `dispatch/` — outbound FIX dispatch (validate → build → send)
- `grpc/` — `LpHedgeGatewayService`: `SubmitMarketOrder`, `EnsureSession`,
  `GetSessionStatus`, `CloseSession`
- `events/`, `persistence/`, `health/`, `admin/`

## Security

- **The gRPC port carries LP FIX passwords.** Enable mTLS
  (`fix-gateway.grpc.tls.*`) in every environment that reaches a real LP.
  Plaintext is for loopback development only, and the gateway logs a warning
  when it starts that way.
- Credentials are redacted in `LpSessionSpec.toString()`, `SessionMetadata`, and
  the outbound Logon log line. QuickFIX's own `FileLogFactory` still writes raw
  FIX to `data/log/` — restrict access to that directory.
- Nothing in `data/` or any `*.pem`/`*.key` is committed; see `.gitignore`.

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
