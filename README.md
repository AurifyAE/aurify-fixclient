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
  `ProviderAdapterRegistry`, `OutboundPolicy`, and three concrete adapters:
  `provider/fxcubic/` (FXCubic FIX API v1.2.2, FIX 4.3), `provider/finalto/`
  (Finalto/CFH FIX API v2.6, FIX 4.4) and `provider/stonex/` (StoneX over
  Integral FX Inside FIX Client API 5.1, FIX 4.3). Adding a provider is five files plus
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

A venue can also need a header field that is per-account rather than
per-venue, which no adapter can supply on its own. StoneX was the first, and
added two optional fields to the contract (both empty for every other venue):

- `FixSession.sender_sub_id` → SenderSubID(50). It goes into the QuickFIX
  `SessionID`, which is how QuickFIX/J stamps it on every message, Logon
  included. Empty, the `SessionID` is identical to before, so existing
  sessions keep their stores and sequence numbers.
- `LpSessionSpec.deliver_to_comp_id` → DeliverToCompID(128), carried on the
  canonical order and market-data requests and on `LpSessionEntry` (the
  post-logon subscribe sees only a `SessionID`).

## StoneX (Integral FX Inside)

Built from *FX Inside FIX Client API 5.1*; **not yet run against the venue's
demo** - treat the first demo session as the conformance test. The LP account
for the demo credentials StoneX issued looks like this (values from their
email; the password belongs in the LP account, never in this repo):

| Field | Order session (`trading`) | Quote session (`pricing`) |
|---|---|---|
| host : port | 208.184.46.37 : 13976 | 208.184.46.37 : 13975 |
| sender_comp_id | `order.APITESTINTM.082` | `quote.APITESTINTM.082` |
| target_comp_id | `demo.fxgrid` | `demo.fxgrid` |
| sender_sub_id | `APITESTINTM` | `APITESTINTM` |
| username / password | `API82` / (issued) | same |

plus `provider: "stonex"`, `fix_version: "FIX.4.3"`,
`deliver_to_comp_id: "BRKD"`, and symbols in either spelling (`XAUUSD` or
`XAU/USD`; the wire form is always `XAU/USD`).

What the adapter does differently from the other two:

- NewOrderSingle carries Currency(15) = base currency (so quantity is in
  ounces for XAU/USD), ExecInst(18)=`B` (without it a market order never
  crosses), MinQty(110) = 0 for IOC / full quantity for FOK, Product(460)=4,
  SecurityType(167)=`FOR`.
- MarketDataRequest carries DeliverToCompID and Product=4 in the symbol group.
- `OrdStatus=7` (Stopped) is how FX Inside refuses an order - mapped to
  REJECTED so the caller is answered, not timed out. `C` (Expired) is
  CANCELLED. Fills arrive as `ExecType=F`, and the New ack may be skipped.
- `FIX43-stonex.xml`: the ExecutionReport may carry MidPx(631) and a few
  other sub-5000 tags the stock FIX 4.3 ExecutionReport does not allow, and
  ExecInst values such as `ST` that are not FIX enums.
- Quotes: entries with price or size 0, or QuoteCondition=`B` (inactive),
  are ignored, per the spec; the best remaining bid/offer is reported.

Open with StoneX before trusting it:

- whether plain **Market orders (OrdType=1)** are enabled for this account,
  or only ESP (OrdType=D against a QuoteEntryID) - the gateway sends Market
  and Limit only;
- gold's symbol, unit and minimum size.

Sequence numbers: FX Inside resets them at **17:00 New York time (EST, no
daylight shift = 22:00 UTC)**, not on logon, so set `reset_seq_num_on_logon:
false` and a session window around that time, e.g. `start_time: 22:00:30`,
`end_time: 21:59:30` (QuickFIX session times are UTC).

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

## Quantity units are Node's decision

`SubmitMarketOrderRequest.quantity` (int64) is the legacy field; Node now
sends `quantity_decimal` (a decimal string in venue units, e.g. "3.21" troy
oz) whenever it is non-empty, and the gateway prefers it. The gateway does
not know or care whether a symbol trades in grams or troy ounces - that
conversion happens on the Node side (`utils/lpQuantityUnits.js`) before the
request is built. `quantity_decimal` is validated (non-numeric, ≤ 0, or scale
> 8 decimal places → `INVALID_QUANTITY`) and, when `max_quantity` is set,
capped (`QUANTITY_ABOVE_MAX`) in `LpHedgeGatewayService.resolveOrderQty`
before a session is touched - see `GRPC-LP-HEDGE.md` for the full contract.
`OutboundPolicy.exceedsMaxOrderSize` takes a `BigDecimal` now so a fractional
order quantity is compared exactly against `max_order_size` rather than
truncated to a `long` first; `max_order_size` itself is unaffected by this
change (Node sends `0`/disabled since the per-order cap moved to
`max_quantity`).

## Order latency by stage

Every SubmitMarketOrder is timed through its lifecycle by
`OrderLatencyTracker`, so the gateway's own time can be told apart from the
LP's:

| Stage | From → to | What it is |
|---|---|---|
| `session` | call received → trading session ready | ~0 when warm; the FIX logon on the first order (`session=cold`) |
| `outbound` | session ready → `sendToTarget` returned | validation, FIX build, message store write |
| `lp_first_report` | sent → first report reaches `fromApp` | network + LP ack |
| `lp_terminal_report` | sent → the terminal report reaches `fromApp` | network + LP decision |
| `inbound` | terminal report in `fromApp` → order settled | cracker, mapping, inbound queue, pipeline, journal |
| `response` | settled → gRPC response written | journal read and response write |
| `total` | call received → response written | the whole call as the gateway sees it |

Each finished order logs one line, keyed by ClOrdID (the caller's order id):

```
Order latency 6718...: total=96.4ms session=0.0ms(warm) outbound=2.1ms lpFirstReport=41.3ms lpTerminalReport=91.7ms inbound=1.9ms response=0.6ms provider=fxcubic outcome=filled
```

The same stages are Micrometer timers, `fix.order.latency{stage,provider,session,outcome}`,
with percentile histograms on `/actuator/prometheus`. Example p95 per stage:

```
histogram_quantile(0.95, sum by (le, stage) (rate(fix_order_latency_seconds_bucket{session="warm"}[5m])))
```

Outcomes are `filled`, `rejected`, `cancelled`, `lp_reject`, `timeout`,
`send_failed` and `error`. A stage that was never reached (no report before a
timeout, say) is left out rather than recorded as zero. Duplicate submits that
wait on an order already in flight are not timed.

## Fake LP for load tests

`src/test/java/com/aurify/fixclient/loadtest/FakeLp.java` is a stand-in
liquidity provider: a FIX acceptor that answers each NewOrderSingle with an
acknowledgement and then a fill, partial fill, no-fill or reject, with
configurable delays, outcome mix, price drift and rate limit. It accepts any
comp ID pair on one port, so each test LP account gets its own session. It is
test code and never ships in the jar.

```bash
mvn test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=com.aurify.fixclient.loadtest.FakeLp -Dfakelp.profile=fxcubic -Dfakelp.port=9878
```

Settings are the `-Dfakelp.*` properties in `FakeLpConfig.fromSystemProperties()`.
`FakeLpGatewayTest` runs the real gateway against it for both profiles. The
full load-test procedure, including seeding the API's database, is
`docs/LOAD_TESTING.md` in Aurify-BullionPro-Nodejs.

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
