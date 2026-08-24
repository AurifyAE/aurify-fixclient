# Multi-Provider FIX Gateway — Architecture & Scaffold
### For bare-metals / FX liquidity connectivity (FXCubic as first adapter)

---

## 0. Relevance of your equities FIX experience — direct answer

Your instinct is right that the architecture transfers. Here's specifically what does and doesn't:

**Transfers almost unchanged (this is 80% of the engineering effort):**
- Session lifecycle management (Logon/Logout/Heartbeat/TestRequest/ResendRequest/SequenceReset)
- Thin `Application` callbacks + `MessageCracker` typed routing
- Separation of transport → canonical DTO → business logic → outbound builder
- Async/reactive processing off the QuickFIX callback thread
- Session registry, sequence number control, admin start/stop endpoints
- Resilience (circuit breakers, retries), observability, persistence-as-optional-audit-trail
- Config-driven session definitions (SessionSettings, per-session sender/target comp IDs)

**Does NOT transfer — this is where LP/FX-specific adapters must absorb complexity:**
- **Session topology**: equities is usually one FIX session per venue/broker. FX/LP is commonly **dual-session per provider** — a pricing session and a separate trading session, each with independent sequence/reset behavior (FXCubic explicitly requires `ResetSeqNumFlag=Y` on the pricing session, and recommends *not persisting* messages for max throughput — the opposite of what you'd typically do for a regulated equities audit trail).
- **Pricing is a first-class message flow.** Equities execution-only clients often don't touch `MarketDataRequest`/`MarketDataSnapshotFullRefresh` at all. Here, market data subscribe/snapshot/refresh is as central as order routing, and needs its own canonical model and its own throughput profile (market data is high-frequency, low-latency, and *must never* share a queue/thread pool with order flow — a slow order path must not delay quotes and vice versa).
- **Order semantics are narrower but stricter.** FXCubic only supports Market and Limit-IOC (`TimeInForce=3` always, `OrdType` 1 or 2 only) — no GTC, no partial-fill-then-rest, no complex order types you may have handled on an exchange. But there are FX-specific fields you won't have dealt with: `ClOrdLinkID` (tag 583) packs account/ticket/group into one string field with a mandatory format, `HandInst` is fixed at `1`, and price on a limit order represents a "worst acceptable price" including slippage tolerance — a different semantic than a resting limit price on an order book.
- **No central limit order book semantics** — this is RFQ/streaming-quote-and-trade-against-it, not a matching engine. `OrdStatus` values are simpler (New/PendingNew/Filled/Rejected — no PartiallyFilled/Replaced/DoneForDay complexity in this spec).
- **Symbol format is LP-specific and unstandardized** (FXCubic notes format "depends on Maker preference" — usually `EURUSD` but not guaranteed across providers), so symbol normalization has to be a pluggable per-provider concern, not a shared static mapping table.
- **FIX version**: FXCubic is FIX 4.3, older equities venues you worked with may have been 4.2/4.4/5.0 — dictionary handling per-provider is not optional, it's structural.

**Bottom line:** keep every layer of your prior architecture (transport isolation, cracker routing, canonical model, async pipeline, session registry, resilience/observability). Push all FX-specific knowledge (dual sessions, quote/price flow, ClOrdLinkID encoding, symbol normalization, IOC-only order semantics) down into the **provider adapter layer**, never into shared services. That's the one architectural rule that matters most here.

---

## 1. Package Structure

```
com.yourorg.fixgateway
├── FixGatewayApplication.java
├── config/
│   ├── ProviderProperties.java              (@ConfigurationProperties, YAML-bound)
│   ├── QuickFixSessionConfigFactory.java
│   ├── AsyncPipelineConfig.java
│   ├── PersistenceConfig.java
│   └── ResilienceConfig.java
├── transport/
│   ├── GatewayFixApplication.java           (QuickFIX Application, thin)
│   ├── GatewayMessageCracker.java
│   ├── SessionLifecycleListener.java
│   └── FixMessageDirection.java             (enum: INBOUND/OUTBOUND, for logging)
├── session/
│   ├── ProviderSessionRegistry.java
│   ├── DirectSessionControlService.java
│   ├── ProviderSessionSettingsManager.java
│   └── SessionRole.java                     (enum: PRICING, TRADING)
├── canonical/
│   ├── event/
│   │   ├── CanonicalExecutionReport.java
│   │   ├── CanonicalOrderRequest.java
│   │   ├── CanonicalCancelRequest.java
│   │   ├── CanonicalReject.java
│   │   ├── CanonicalQuote.java
│   │   ├── CanonicalMarketDataSnapshot.java
│   │   ├── CanonicalSessionEvent.java
│   │   └── CanonicalProviderHealthEvent.java
│   └── enums/
│       ├── CanonicalOrdStatus.java
│       ├── CanonicalExecType.java
│       └── CanonicalSide.java
├── provider/
│   ├── LiquidityProviderAdapter.java         (interface)
│   ├── AbstractQuickFixProviderAdapter.java  (abstract base)
│   ├── ProviderCapabilities.java
│   ├── ProviderAdapterRegistry.java
│   └── fxcubic/
│       ├── FxCubicProviderAdapter.java
│       ├── FxCubicIncomingMapper.java
│       ├── FxCubicOutgoingBuilder.java
│       ├── FxCubicStartupWorkflow.java
│       ├── FxCubicSymbolNormalizer.java
│       └── FxCubicValidationRules.java
├── pipeline/
│   ├── InboundMessageQueue.java              (Reactor Sinks-backed)
│   ├── OutboundRequestQueue.java
│   ├── InboundProcessingPipeline.java
│   ├── OutboundDispatchPipeline.java
│   └── BackpressureConfig.java
├── dispatch/
│   ├── OutboundFixDispatchService.java
│   ├── NewOrderDispatcher.java
│   ├── CancelReplaceDispatcher.java
│   ├── CancelDispatcher.java
│   ├── MarketDataSubscriptionDispatcher.java
│   └── OrderStatusRequestDispatcher.java
├── events/
│   ├── GatewayEventPublisher.java
│   ├── SessionConnectedEvent.java
│   ├── SessionDisconnectedEvent.java
│   ├── ProviderHealthChangedEvent.java
│   └── PipelineStateChangedEvent.java
├── persistence/
│   ├── PersistenceGateway.java               (interface, pluggable)
│   ├── InMemoryPersistenceAdapter.java
│   ├── JdbcPersistenceAdapter.java
│   └── entity/
│       ├── RawFixMessageEntity.java
│       └── CanonicalEventEntity.java
├── health/
│   ├── ProviderHealthIndicator.java          (Actuator HealthIndicator)
│   ├── SessionMetricsCollector.java          (Micrometer)
│   └── QueueDepthMetrics.java
├── admin/
│   ├── AdminSessionController.java
│   ├── AdminSequenceController.java
│   ├── AdminProviderController.java
│   └── dto/
│       ├── SessionStatusResponse.java
│       └── SequenceResetRequest.java
└── resilience/
    ├── DownstreamCircuitBreakerConfig.java
    └── RetryPolicyConfig.java
```

---

## 2. High-Level Architecture

Four concerns, strictly layered, same as your equities system:

```
 [QuickFIX/J transport]  →  [canonical transformation]  →  [async pipeline]  →  [business/downstream]
        thin                    provider adapter              backpressure          admin/publish/persist
```

The one addition specific to this domain: **two parallel pipelines**, not one — a market-data (pricing) pipeline and an order/trading pipeline — sharing the session registry and event bus but *not* sharing queues or thread pools. This matters because FXCubic (and most LPs) run pricing and trading as physically separate FIX sessions with different throughput/latency profiles.

```
                         ┌─────────────────────────────┐
                         │   ProviderSessionRegistry    │
                         │ (PRICING sess / TRADING sess │
                         │   per provider, keyed by     │
                         │   SenderCompID+TargetCompID) │
                         └───────────┬──────────────────┘
                                     │
        ┌────────────────────────────────────────────────────┐
        │                    GatewayFixApplication             │
        │  onLogon/onLogout/toAdmin/fromAdmin/toApp/fromApp     │
        │       (thin — delegates immediately, no parsing)      │
        └───────────┬────────────────────────┬─────────────────┘
                     │                        │
             fromApp(pricing)         fromApp(trading)
                     │                        │
                     ▼                        ▼
          ┌─────────────────┐       ┌─────────────────┐
          │ GatewayMessage   │       │ GatewayMessage   │
          │ Cracker (pricing)│       │ Cracker (trading)│
          └────────┬─────────┘       └────────┬─────────┘
                    │ typed dispatch            │ typed dispatch
                    ▼                            ▼
        ProviderAdapter.mapIncoming()  ProviderAdapter.mapIncoming()
                    │                            │
                    ▼                            ▼
          CanonicalQuote /                CanonicalExecutionReport /
          CanonicalMarketDataSnapshot     CanonicalReject / CanonicalSessionEvent
                    │                            │
                    ▼                            ▼
          InboundMessageQueue(pricing)   InboundMessageQueue(trading)
           (Reactor Sinks, bounded)       (Reactor Sinks, bounded)
                    │                            │
                    ▼                            ▼
          InboundProcessingPipeline       InboundProcessingPipeline
           → publish CanonicalEvent        → publish CanonicalEvent
           → optional persistence          → optional persistence
           → GatewayEventPublisher          → GatewayEventPublisher

        ────────────────────── outbound (trading only, typically) ──────────────────────

  Caller/API → CanonicalOrderRequest → OutboundRequestQueue → OutboundDispatchPipeline
                                                                     │
                                                     resolve ProviderAdapter + Session
                                                                     │
                                                     ProviderAdapter.buildOutgoing()
                                                                     │
                                                        Session.send() (QuickFIX)
                                                                     │
                                                     mark success/failure, emit event
```

---

## 3. Key Interfaces & Abstract Classes

```java
public interface LiquidityProviderAdapter {

    String providerName();

    ProviderCapabilities capabilities();

    // Incoming: raw FIX -> canonical
    CanonicalEvent mapIncoming(Message fixMessage, SessionID sessionId) throws FieldNotFound;

    // Outgoing: canonical -> raw FIX
    Message buildOutgoing(CanonicalOutboundRequest request, SessionID sessionId);

    // Called once per session after successful Logon
    void onPostLogonStartup(SessionID sessionId, DirectSessionControlService sessionControl);

    // LP-specific structural/business validation before send
    ValidationResult validateOutbound(CanonicalOutboundRequest request);

    // Symbol translation is provider-specific by spec ("depends on Maker preference")
    String normalizeSymbol(String rawSymbol);
}
```

```java
public abstract class AbstractQuickFixProviderAdapter implements LiquidityProviderAdapter {

    protected final ProviderSessionSettingsManager settingsManager;
    protected final GatewayEventPublisher eventPublisher;

    protected AbstractQuickFixProviderAdapter(ProviderSessionSettingsManager settingsManager,
                                               GatewayEventPublisher eventPublisher) {
        this.settingsManager = settingsManager;
        this.eventPublisher = eventPublisher;
    }

    // Shared header population (SenderCompID/TargetCompID/SendingTime) — not LP-specific
    protected final void applyStandardHeader(Message message, SessionID sessionId) {
        message.getHeader().setField(new SendingTime(LocalDateTime.now(ZoneOffset.UTC)));
    }

    // Template method — subclasses implement mapIncoming/buildOutgoing/onPostLogonStartup
    @Override
    public ValidationResult validateOutbound(CanonicalOutboundRequest request) {
        return ValidationResult.ok(); // default no-op, override per provider
    }
}
```

```java
public interface ProviderSessionRegistry {
    Optional<SessionID> resolve(String providerName, SessionRole role);
    Optional<SessionID> resolveByCompIds(String senderCompId, String targetCompId);
    List<SessionID> allSessionsFor(String providerName);
    void register(String providerName, SessionRole role, SessionID sessionId);
}
```

```java
public interface DirectSessionControlService {
    void logon(SessionID sessionId);
    void logout(SessionID sessionId, String reason);
    void start(SessionID sessionId);
    void stop(SessionID sessionId, boolean forceDisconnect);
    void setNextOutboundSeqNum(SessionID sessionId, int seqNum);
    void setNextInboundSeqNum(SessionID sessionId, int seqNum);
    SessionStatusSnapshot statusOf(SessionID sessionId);
}
```

```java
public interface PersistenceGateway {
    void persistRawInbound(SessionID sessionId, String rawFix);
    void persistRawOutbound(SessionID sessionId, String rawFix);
    void persistCanonicalEvent(CanonicalEvent event);
    void persistFailedPublish(CanonicalEvent event, Throwable cause);
}
```

---

## 4. Session Lifecycle Flow

```
1. Application startup
   → ProviderProperties bound from YAML
   → QuickFixSessionConfigFactory builds SessionSettings per provider/role (PRICING, TRADING)
   → SocketInitiator started

2. onCreate(SessionID)
   → ProviderSessionRegistry.register(providerName, role, sessionId)

3. onLogon(SessionID)
   → DirectSessionControlService marks session ACTIVE
   → GatewayEventPublisher.publish(SessionConnectedEvent)
   → ProviderAdapter.onPostLogonStartup(sessionId, sessionControl)
       (e.g. FxCubicStartupWorkflow subscribes top-of-book market data
        for configured symbol list on the PRICING session only —
        trading sessions have no post-logon subscription per FXCubic spec)

4. Steady state
   → Heartbeat/TestRequest handled entirely inside QuickFIX/J engine (toAdmin/fromAdmin thin passthrough)
   → fromApp() routes application messages via MessageCracker

5. onLogout(SessionID) / disconnect
   → GatewayEventPublisher.publish(SessionDisconnectedEvent)
   → ProviderHealthIndicator flips DOWN for that session
   → reconnect handled by QuickFIX/J SocketInitiator reconnect settings (config-driven)
```

Session-role note: per FXCubic spec, `ResetSeqNumFlag=Y` is **mandatory on the pricing session** and only optional on trading — this must live in `ProviderSessionSettingsManager` as per-role config, not a global default, because other LPs may differ.

---

## 5. Incoming Message Flow

```
fromApp(Message, SessionID)              // GatewayFixApplication — thin
   → GatewayMessageCracker.crack(message)  // typed dispatch by MsgType
       case ExecutionReport   → handler.onExecutionReport(message, sessionId)
       case MarketDataSnapshotFullRefresh → handler.onMarketDataSnapshot(...)
       case MarketDataRequestReject → handler.onMarketDataReject(...)
       case Reject / BusinessMessageReject → handler.onReject(...)

   → each handler resolves ProviderAdapter via ProviderAdapterRegistry (by SessionID's provider)
   → adapter.mapIncoming(message, sessionId) → CanonicalEvent
   → InboundMessageQueue.offer(event)   // Reactor Sinks.many(), bounded, non-blocking
        (if full: configurable — drop-oldest for market data, error-and-alert for execution reports)

InboundProcessingPipeline (subscribes to the Sinks Flux on a bounded elastic scheduler)
   → optional PersistenceGateway.persistCanonicalEvent(event)
   → GatewayEventPublisher.publish(event)         // internal consumers (admin UI, downstream systems)
   → metrics: SessionMetricsCollector.recordInbound(event)
```

No business logic ever executes on the QuickFIX network thread — `fromApp` does nothing but crack + map + enqueue, all of which are non-blocking, bounded operations.

---

## 6. Outgoing Request Flow

```
Caller (REST/internal) → CanonicalOrderRequest { provider, clOrdId, symbol, side, qty, ordType, price?, ... }
   → OutboundRequestQueue.offer(request)             // Reactor Sinks, bounded, backpressure-aware
   → OutboundDispatchPipeline (subscriber)
       → ProviderAdapterRegistry.resolve(request.provider())
       → adapter.validateOutbound(request)            // e.g. FXCubic: reject if ordType=Limit and price missing,
                                                        //   or if TIF != IOC (spec only supports IOC)
       → ProviderSessionRegistry.resolve(provider, TRADING)
       → adapter.buildOutgoing(request, sessionId)     // canonical -> FIX NewOrderSingle
           - sets ClOrdLinkID (tag 583) from account/ticket/group per FXCubic mandatory format
           - sets HandInst=1, TimeInForce=3 (IOC) always
       → Session.sendToTarget(fixMessage, sessionId)
       → on success: persist + publish CanonicalSessionEvent(SENT)
       → on failure: Resilience4j retry policy → circuit breaker → persist failed publish
```

`NewOrderDispatcher`, `CancelDispatcher`, `MarketDataSubscriptionDispatcher`, `OrderStatusRequestDispatcher` are separate classes implementing a common `OutboundFixDispatcher<T extends CanonicalOutboundRequest>` — no giant switch statement; `OutboundFixDispatchService` picks the dispatcher by request type via a `Map<Class<?>, OutboundFixDispatcher<?>>` built at startup.

---

## 7. FXCubic Adapter Design

Concrete mapping decisions baked into `FxCubicProviderAdapter` (nothing here leaks into shared code):

| Concern | FXCubic rule | Where it lives |
|---|---|---|
| FIX version | 4.3 dictionary | `FxCubicProviderAdapter.capabilities().fixVersion()` |
| Sessions | 2 required: PRICING + TRADING | `ProviderSessionSettingsManager` config, role-tagged |
| Pricing session reset | `ResetSeqNumFlag=Y` mandatory | session YAML for role=PRICING |
| Message persistence | disabled for max throughput (both sessions) | QuickFIX `PersistMessages=N` in session config |
| Order types | Market(1) or Limit(2) only, TIF always IOC(3) | `FxCubicOutgoingBuilder` hardcodes TIF, validates OrdType |
| HandInst | always `1` | `FxCubicOutgoingBuilder` |
| ClOrdLinkID (583) | mandatory string `"{ticketId}-{accountId}-{group}"`, all three populated even if unused | `FxCubicOutgoingBuilder.buildClOrdLinkId()` |
| SecondaryClOrdID (526) | reporting-only, from ExecutionReport | `FxCubicIncomingMapper` |
| Symbol format | Maker-dependent, usually `EURUSD` | `FxCubicSymbolNormalizer` — pluggable, provider-owned |
| Post-logon startup | none mandated by spec beyond auth; MD subscription optional/config-driven | `FxCubicStartupWorkflow`, only acts on PRICING session |
| Market data subscribe | `MarketDataRequest` with both Bid(0) and Offer(1) entry types, one symbol per request recommended | `FxCubicOutgoingBuilder.buildMarketDataRequest()` |
| Reject handling | `MDReqRejReason`: UnknownSymbol(0) / DuplicateMDReqID(1) | `FxCubicIncomingMapper.mapMarketDataReject()` |

---

## 8. Example YAML Config (multi-provider)

```yaml
fix-gateway:
  providers:
    fxcubic:
      display-name: "FXCubic"
      fix-version: "FIX.4.3"
      sessions:
        pricing:
          sender-comp-id: "YOURFIRM_PX"
          target-comp-id: "FXCUBIC_PX"
          host: "px.fxcubic.example.com"
          port: 9001
          reset-seq-num-on-logon: true
          persist-messages: false
          heartbeat-interval-seconds: 30
          start-time: "00:00:00"
          end-time: "23:59:59"
          use-ssl: true
        trading:
          sender-comp-id: "YOURFIRM_TR"
          target-comp-id: "FXCUBIC_TR"
          host: "tr.fxcubic.example.com"
          port: 9002
          reset-seq-num-on-logon: false
          persist-messages: false
          heartbeat-interval-seconds: 30
          use-ssl: true
      startup:
        subscribe-market-data: true
        symbols: ["EURUSD", "GBPUSD", "USDJPY"]
        market-depth: "TOP_OF_BOOK"
      capabilities:
        supported-ord-types: ["MARKET", "LIMIT"]
        supported-tif: ["IOC"]
      credentials:
        username: "${FXCUBIC_USERNAME}"
        password: "${FXCUBIC_PASSWORD}"

    primexm:
      display-name: "PrimeXM"
      fix-version: "FIX.4.4"        # confirm against actual PrimeXM spec once available
      sessions:
        pricing: { ... }
        trading: { ... }
      startup:
        subscribe-market-data: true
        symbols: ["EURUSD", "XAUUSD"]
      capabilities:
        supported-ord-types: ["MARKET", "LIMIT"]
        supported-tif: ["IOC", "FOK"]
      credentials:
        username: "${PRIMEXM_USERNAME}"
        password: "${PRIMEXM_PASSWORD}"

  pipeline:
    inbound-queue-capacity: 10000
    outbound-queue-capacity: 5000
    overflow-strategy: DROP_OLDEST   # market-data queues; trading queues use ERROR
  persistence:
    mode: jdbc                       # in-memory | jdbc
  resilience:
    circuit-breaker:
      failure-rate-threshold: 50
      wait-duration-in-open-state: 30s
    retry:
      max-attempts: 3
      backoff-ms: 200
```

---

## 9. QuickFIX Configuration Strategy

- Do **not** hand-author static `.cfg` files per provider; generate `SessionSettings` programmatically in `QuickFixSessionConfigFactory` from the bound `ProviderProperties`, so YAML remains the single source of truth and new providers require zero code changes to the config layer.
- One `SocketInitiator` per JVM, holding all sessions across all providers — session identity is `SenderCompID+TargetCompID+SessionQualifier(role)`.
- Custom data dictionaries per provider go in `src/main/resources/dictionaries/{provider}-{version}.xml`; `QuickFixSessionConfigFactory` sets `DataDictionary` path per session from provider config, not hardcoded.
- `PersistMessages=N` for FXCubic-style LPs (per spec, for throughput) — but keep this a per-session YAML toggle since other providers may require message store persistence for resend-request compliance.

---

## 10. Persistence Model (pluggable)

```
PersistenceGateway (interface)
 ├── InMemoryPersistenceAdapter   — dev/test, ring-buffer bounded
 └── JdbcPersistenceAdapter       — production, PostgreSQL

Tables (JDBC):
  raw_fix_message(id, session_id, direction, provider, raw_text, received_at)
  canonical_event(id, event_type, provider, payload_json, correlation_id, created_at)
  failed_publish(id, event_type, payload_json, error_message, retry_count, created_at)
```

Persistence is entirely optional per environment (`persistence.mode: in-memory` for dev) and asynchronous — writes happen inside `InboundProcessingPipeline`/`OutboundDispatchPipeline`, never inline with FIX I/O, never able to block a session thread.

---

## 11. Logging & Monitoring Model

- **Direction-tagged structured logs**: every raw FIX message logged once at DEBUG (full) and a normalized summary at INFO (`MsgType`, `ClOrdID`/`MDReqID`, session, direction, latency-to-process).
- **Actuator health**: `ProviderHealthIndicator` aggregates per-session status (`UP`/`DOWN`/`DEGRADED`) into `/actuator/health`.
- **Micrometer metrics**: `fixgateway.session.status`, `fixgateway.queue.depth{queue=inbound-pricing|inbound-trading|outbound}`, `fixgateway.messages.count{direction,provider,msgtype}`, `fixgateway.last_logon_timestamp{session}`.
- **Admin visibility**: `/admin/sessions` lists all sessions + last logon/logoff + current seq numbers; `/admin/sessions/{id}/sequence` for manual reset.

---

## 12. Step-by-Step Implementation Roadmap

1. Stand up `FixGatewayApplication` + `QuickFixSessionConfigFactory` against a **single FXCubic pricing session only** — get Logon/Heartbeat/Logout working end-to-end first.
2. Add `GatewayMessageCracker` + `FxCubicIncomingMapper` for `MarketDataSnapshotFullRefresh` → `CanonicalQuote`, wire to `InboundMessageQueue` → log-only consumer.
3. Add the trading session; implement `FxCubicOutgoingBuilder.buildNewOrderSingle()` + `NewOrderDispatcher`; validate against FXCubic's IOC-only/HandInst=1/ClOrdLinkID rules.
4. Implement `FxCubicIncomingMapper` for `ExecutionReport` → `CanonicalExecutionReport`; close the loop (send order → receive fill).
5. Add `ProviderSessionRegistry`, `DirectSessionControlService`, admin controllers — session start/stop/sequence control.
6. Add `GatewayEventPublisher` + Actuator health/metrics.
7. Add `PersistenceGateway` with in-memory adapter, then JDBC adapter.
8. Add Resilience4j circuit breaker/retry around `OutboundDispatchPipeline` and any downstream publish.
9. Add `FxCubicStartupWorkflow` (post-logon MD subscription) driven by YAML.
10. Generalize: extract a second dummy provider adapter (even a stub) to prove `LiquidityProviderAdapter` abstraction actually holds with zero shared-service changes — this is the real test of the design, do it before declaring FXCubic "done."
11. Swap in real PrimeXM spec once available; add `PrimeXmProviderAdapter` following the exact same steps 1–9 pattern.

---

## 13. Risks / Tradeoffs

- **Dual-session-per-provider is a structural assumption** baked into `ProviderSessionRegistry`/`SessionRole`. If a future LP uses a single combined session, `SessionRole` becomes optional/nullable rather than required — plan for that now in the registry's API (already reflected above: `resolve(provider, role)` returns `Optional`).
- **Market-data volume vs. persistence cost**: persisting every tick is expensive and mostly unnecessary; recommend persisting canonical *order/execution* events always, but making market-data persistence sampled/off by default — configurable per provider.
- **IOC-only orders simplify state management** (no resting orders to track) for FXCubic specifically, but the canonical `CanonicalOrderRequest`/`CanonicalExecutionReport` model must not assume this globally, since other LPs (and your roadmap item on PrimeXM) may support GTC/resting orders — the canonical model should carry `TimeInForce` explicitly rather than assuming IOC.
- **Symbol normalization is a real risk area**: since format is "Maker preference," a bad assumption here silently misroutes market data or order symbols. Recommend unit-testing `FxCubicSymbolNormalizer` against a static list of confirmed symbols from FXCubic before going live, and failing loudly (not silently passing through) on unknown symbols.
- **Why Reactor over plain `ExecutorService` queues** (as your equities system may have used): bounded backpressure semantics are native to Reactor Sinks and compose cleanly with WebFlux/Actuator if you later expose streaming endpoints; if your team has no Reactor experience, a simpler bounded `BlockingQueue` + dedicated consumer threads achieves the same isolation guarantees with less learning curve — worth a team discussion before committing.
