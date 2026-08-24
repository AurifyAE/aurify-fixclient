# FIX Gateway Scaffold

Read **FIX-GATEWAY-ARCHITECTURE.md** first — it covers the relevance analysis,
full architecture, message flows, YAML config strategy, and implementation
roadmap.

## What's here

Working package structure + code **skeletons** (not a compiling, tested
project) under `src/main/java/com/yourorg/fixgateway/`, covering:

- `transport/` — thin `GatewayFixApplication`, `GatewayMessageCracker`
- `session/` — `ProviderSessionRegistry`, `DirectSessionControlService` (+ impls)
- `canonical/` — provider-agnostic DTOs and enums
- `provider/` — `LiquidityProviderAdapter` interface, `AbstractQuickFixProviderAdapter`,
  `ProviderAdapterRegistry`, and a concrete `provider/fxcubic/` adapter implementing
  every rule from the attached FXCubic FIX API v1.2.2 spec
- `pipeline/` — Reactor-based bounded inbound/outbound queues + pipelines
- `dispatch/` — outbound FIX dispatch service (validate → resolve session → build → send)
- `events/` — internal event publisher + event types
- `persistence/` — pluggable `PersistenceGateway` (in-memory adapter included, JPA entities scaffolded)
- `health/` — Actuator health indicator + Micrometer metrics
- `admin/` — REST controllers for session/sequence/provider control

## Known gaps (intentional, this is a scaffold not a finished service)

- `QuickFixSessionConfigFactory` (binds `application.yml` → QuickFIX `SessionSettings`)
  is described in the architecture doc but not yet coded — this is the next thing to build,
  see roadmap step 1.
- JDBC `PersistenceGateway` implementation is not included (only the JPA entities + in-memory adapter).
- No tests yet — roadmap step 10 explicitly calls out adding a second stub provider
  adapter as the real test of the abstraction before calling FXCubic "done."
- PrimeXM adapter is not built — the actual PrimeXM FIX spec wasn't part of what you
  uploaded (the attached doc is FXCubic's). Once you have PrimeXM's spec, mirror the
  `provider/fxcubic/` package exactly.

## Build

```
mvn spring-boot:run
```

(after filling in `QuickFixSessionConfigFactory` and setting `FXCUBIC_USERNAME`/`FXCUBIC_PASSWORD`)
