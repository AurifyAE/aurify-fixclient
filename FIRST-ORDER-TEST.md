# Running Your First Test Order Against FXCubic

This is the fastest path to a real Logon → Order → Confirmation test, using
a standalone runner that skips the full scaffold on purpose.

## Before you run anything

Get these from FXCubic (ask your onboarding contact):

- [ ] Demo/UAT **host** and **port** for the *trading* session
- [ ] **SenderCompID** and **TargetCompID** for that demo session
- [ ] A demo **username** and **password**
- [ ] Confirmation that **EURUSD** (or whichever symbol) is tradeable in demo
- [ ] The FIX 4.3 data dictionary file (`FIX43.xml`) — this ships with
      QuickFIX/J, but confirm FXCubic doesn't use a customized version

**Do not point this at a production endpoint.**

## Where the test lives

`src/test/java/com/yourorg/fixgateway/manualtest/`
- `ManualFirstOrderTest.java` — the runnable entry point
- `ManualTestApplication.java` — a minimal, throwaway QuickFIX handler
  (not the real production code — just enough to prove connectivity)

## Steps

1. Open `ManualFirstOrderTest.java` and fill in the placeholder values at the top:
   ```java
   SENDER_COMP_ID, TARGET_COMP_ID, HOST, PORT, USERNAME, PASSWORD, TEST_SYMBOL
   ```
2. Open `ManualTestApplication.java` and set the same `USERNAME` / `PASSWORD` there too
   (kept in both files for now, since this is a quick throwaway test).
3. Make sure `FIX43.xml` is on the classpath (`src/main/resources/` or wherever
   your QuickFIX/J dependency expects it — check the quickfixj-messages-fix43 jar).
4. Run it:
   ```
   mvn compile exec:java -Dexec.mainClass=com.yourorg.fixgateway.manualtest.ManualFirstOrderTest
   ```
   (or just run `main()` directly from your IDE)

## What you should see, in order

1. `Session created: ...`
2. `Logon: ...` — this is your first real checkpoint. If this doesn't happen
   within 15 seconds, stop here — the problem is host/port/credentials, not
   your order logic.
3. `Sending order: ...` — the raw NewOrderSingle being sent
4. `fromApp: ...` followed by `Received ExecutionReport: ...` — this is
   your order confirmation coming back from FXCubic
5. `SUCCESS: received ExecutionReport back from FXCubic.`

## If it doesn't work

- **No Logon** → check host/port reachability first (can you even open a TCP
  connection?), then check SenderCompID/TargetCompID spelling, then credentials.
- **Logon works but order gets rejected** → look at the `Text` (tag 58) and
  `OrdRejReason` (tag 103) fields in the ExecutionReport — FXCubic will tell you
  why (wrong symbol format, unsupported order type, missing ClOrdLinkID, etc).
- **Nothing comes back at all after sending the order** → confirm you're sending
  on the *trading* session, not the *pricing* session — they're separate
  connections per the FXCubic spec.

## After this works

Don't build on top of `ManualTestApplication` — it's a throwaway. Once
Logon → Order → ExecutionReport is proven, move to wiring the real scaffold:
`QuickFixSessionConfigFactory` + `FxCubicOutgoingBuilder` +
`OutboundFixDispatchService`, using the same host/port/credentials in
`application.yml` instead of hardcoded Java constants.
