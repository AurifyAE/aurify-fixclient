package com.aurify.fixclient.manualtest;

/**
 * NOT a JUnit test. This is a standalone, run-it-yourself program for your
 * very first connectivity check against FXCubic's demo/UAT environment.
 *
 * It intentionally skips the whole Spring/adapter/pipeline scaffold so you
 * can prove three things in isolation, in order:
 *   1. We can Logon successfully
 *   2. We can send a NewOrderSingle
 *   3. We receive an ExecutionReport back
 *
 * Run with: mvn compile exec:java -Dexec.mainClass=com.yourorg.fixgateway.manualtest.ManualFirstOrderTest
 * (or run main() directly from your IDE)
 *
 * Fill in the placeholders below with the demo credentials FXCubic gives you
 * before running. DO NOT point this at a production endpoint.
 */
public class ManualFirstOrderTest {

    // ---- fill these in with values from FXCubic's demo/UAT onboarding ----
    private static final String SENDER_COMP_ID = "Olla_T";
    private static final String TARGET_COMP_ID = "FXC_T";
    private static final String HOST = "95.217.231.38";
    private static final int PORT = 9121;
    private static final String USERNAME = "Olla_t";
    private static final String PASSWORD = "5EJsVBC80";
    private static final String TEST_SYMBOL = "EURUSD";
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        quickfix.SessionSettings settings = buildSettings();

        ManualTestApplication application = new ManualTestApplication(TEST_SYMBOL);

        quickfix.MessageStoreFactory storeFactory = new quickfix.FileStoreFactory(settings);
        quickfix.LogFactory logFactory = new quickfix.ScreenLogFactory(settings);
        quickfix.MessageFactory messageFactory = new quickfix.DefaultMessageFactory();

        quickfix.SocketInitiator initiator = new quickfix.SocketInitiator(
                application, storeFactory, settings, logFactory, messageFactory);

        System.out.println("Starting initiator, connecting to " + HOST + ":" + PORT + " ...");
        initiator.start();

        // Wait for logon, then send one order, then wait for the ExecutionReport.
        // This is a deliberately simple/blocking wait loop - fine for a manual
        // one-off test, not something to reuse in production code.
        application.awaitLogon(java.time.Duration.ofSeconds(15));
        System.out.println("Logon confirmed. Sending test order...");

        application.sendTestOrder();

        boolean gotExecReport = application.awaitExecutionReport(java.time.Duration.ofSeconds(15));
        if (gotExecReport) {
            System.out.println("SUCCESS: received ExecutionReport back from FXCubic.");
        } else {
            System.out.println("No ExecutionReport received within timeout - check logs above.");
        }

        Thread.sleep(2000);
        initiator.stop();
    }

    private static quickfix.SessionSettings buildSettings() throws java.io.IOException {
        quickfix.SessionSettings settings = new quickfix.SessionSettings();
        settings.setString("ConnectionType", "initiator");
        settings.setString("FileStorePath", "data/manual-test-store");
        settings.setBool("UseDataDictionary", true);
        settings.setString("DataDictionary", "FIX43.xml");

        quickfix.SessionID sessionId = new quickfix.SessionID("FIX.4.3", SENDER_COMP_ID, TARGET_COMP_ID);
        settings.setString(sessionId, "SocketConnectHost", HOST);
        settings.setLong(sessionId, "SocketConnectPort", PORT);
        settings.setLong(sessionId, "HeartBtInt", 30);
        settings.setBool(sessionId, "ResetOnLogon", true);
        settings.setBool(sessionId, "PersistMessages", false);
        settings.setString(sessionId, "StartTime", "00:00:00");
        settings.setString(sessionId, "EndTime", "23:59:59");
        settings.setString(sessionId, "SocketUseSSL", "Y");

        return settings;
    }
}
