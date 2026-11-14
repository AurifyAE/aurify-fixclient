package com.aurify.fixclient.loadtest;

import quickfix.Application;
import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.FieldNotFound;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.RejectLogon;
import quickfix.ScreenLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;
import quickfix.field.Account;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.MsgType;
import quickfix.field.OrdRejReason;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.Password;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TransactTime;
import quickfix.field.Username;
import quickfix.mina.acceptor.DynamicAcceptorSessionProvider;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A stand-in liquidity provider for load tests: a FIX acceptor that answers
 * every NewOrderSingle the way an LP does (an acknowledgement, then a fill, a
 * partial fill, a no-fill or a reject) with configurable delays and a
 * configurable mix.
 *
 * It accepts any SenderCompID/TargetCompID pair on its one port, so each LP
 * account seeded for a load run gets its own session, as it would at a real LP.
 * Nothing is persisted and nothing is real: point only test LP accounts at it.
 *
 * Run it (settings are listed in {@link FakeLpConfig#fromSystemProperties()}):
 * <pre>
 * mvn test-compile exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=com.aurify.fixclient.loadtest.FakeLp \
 *     -Dfakelp.profile=fxcubic -Dfakelp.port=9878 -Dfakelp.reportFile=fake-lp-orders.csv
 * </pre>
 */
public final class FakeLp implements Application {

    private static final String CSV_HEADER = "received_epoch_ms,lp_session,cl_ord_id,side,ord_type,qty,limit_price,"
            + "outcome,filled_qty,exec_price,ack_after_ms,final_after_ms,text";

    private final FakeLpConfig config;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "fake-lp-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private final Random random;
    private final AtomicLong orderSeq = new AtomicLong();
    private final AtomicLong execSeq = new AtomicLong();
    private final FakeLpStats stats = new FakeLpStats();
    private final BufferedWriter report;
    private BigDecimal marketPrice;
    private long throttleSecond;
    private int ordersThisSecond;
    private SocketAcceptor acceptor;

    private FakeLp(FakeLpConfig config) {
        this.config = config;
        this.random = new Random(config.seed());
        this.marketPrice = config.referencePrice();
        this.report = openReport(config);
    }

    public static void main(String[] args) throws Exception {
        FakeLp lp = start(FakeLpConfig.fromSystemProperties());
        Runtime.getRuntime().addShutdownHook(new Thread(lp::stop, "fake-lp-shutdown"));
        Thread.currentThread().join();
    }

    public static FakeLp start(FakeLpConfig config) throws ConfigError {
        FakeLp lp = new FakeLp(config);
        lp.listen();
        System.out.printf("[fake-lp] %s on port %d: ack %dms, fill %dms (+/-%dms), mix fill/partial/noFill/reject "
                        + "= %d/%d/%d/%d, reference price %s%s%n",
                config.profile(), config.port(), config.ackDelayMs(), config.fillDelayMs(), config.jitterMs(),
                config.fillPct(), config.partialPct(), config.noFillPct(), config.rejectPct(),
                config.referencePrice().toPlainString(),
                config.maxOrdersPerSecond() > 0 ? ", limit " + config.maxOrdersPerSecond() + " orders/s" : "");
        return lp;
    }

    public void stop() {
        if (acceptor != null) {
            acceptor.stop(true);
            acceptor = null;
        }
        scheduler.shutdownNow();
        closeReport();
        System.out.println("[fake-lp] " + stats.summary());
    }

    public FakeLpStats stats() {
        return stats;
    }

    private void listen() throws ConfigError {
        SessionSettings settings = new SessionSettings();
        settings.setString("ConnectionType", "acceptor");
        // The comp IDs are placeholders: the dynamic provider takes the real ones from each Logon.
        SessionID template = new SessionID(config.profile().beginString, "FAKE_LP", "ANY");
        settings.setBool(template, "AcceptorTemplate", true);
        settings.setLong(template, "SocketAcceptPort", config.port());
        settings.setString(template, "StartTime", "00:00:00");
        settings.setString(template, "EndTime", "23:59:59");
        settings.setBool(template, "UseDataDictionary", true);
        // The gateway's own dictionary: a message it builds wrong is refused here, as an LP would.
        settings.setString(template, "DataDictionary", config.profile().dataDictionary);
        settings.setBool(template, "ResetOnLogon", true);
        settings.setBool(template, "ResetOnLogout", true);
        settings.setBool(template, "ResetOnDisconnect", true);

        MessageStoreFactory store = new MemoryStoreFactory();
        MessageFactory messages = new DefaultMessageFactory();
        ScreenLogFactory logs = new ScreenLogFactory(false, false, true);
        acceptor = new SocketAcceptor(this, store, settings, logs, messages);
        acceptor.setSessionProvider(new InetSocketAddress("0.0.0.0", config.port()),
                new DynamicAcceptorSessionProvider(settings, template, this, store, logs, messages));
        acceptor.start();
    }

    // ---------------------------------------------------------------- QuickFIX callbacks

    @Override
    public void onCreate(SessionID sessionId) {
    }

    @Override
    public void onLogon(SessionID sessionId) {
        System.out.println("[fake-lp] logon " + sessionId);
    }

    @Override
    public void onLogout(SessionID sessionId) {
        System.out.println("[fake-lp] logout " + sessionId);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        if (isType(message, MsgType.REJECT)) {
            // A session Reject means the gateway sent something the dictionary refuses. The order then
            // never gets an execution report, so make it loud rather than letting it time out quietly.
            System.out.println("[fake-lp] WARN rejecting a message from " + sessionId + ": " + message);
        }
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId) throws RejectLogon {
        if (!isType(message, MsgType.LOGON) || config.username() == null) {
            return;
        }
        String username = stringOrNull(message, Username.FIELD);
        String password = stringOrNull(message, Password.FIELD);
        if (!config.username().equals(username) || !String.valueOf(config.password()).equals(password)) {
            throw new RejectLogon("Fake LP: wrong username or password");
        }
    }

    @Override
    public void toApp(Message message, SessionID sessionId) {
    }

    @Override
    public void fromApp(Message message, SessionID sessionId) {
        if (isType(message, MsgType.ORDER_SINGLE)) {
            onNewOrder(message, sessionId);
        }
        // Market data requests are ignored: load runs drive trading sessions only.
    }

    // ---------------------------------------------------------------- orders

    private void onNewOrder(Message message, SessionID sessionId) {
        long receivedAt = System.currentTimeMillis();
        LiveOrder order;
        try {
            order = LiveOrder.from(message, "FAKE-" + orderSeq.incrementAndGet());
        } catch (FieldNotFound | RuntimeException e) {
            System.out.println("[fake-lp] WARN unreadable order from " + sessionId + ": " + e.getMessage());
            return;
        }
        stats.received();

        if (throttled(receivedAt)) {
            sendFinal(sessionId, order, new FakeLpOutcomes.Decision(FakeLpOutcomes.Kind.REJECT, BigDecimal.ZERO,
                    null, "Fake LP: rate limit of " + config.maxOrdersPerSecond() + " orders/s exceeded"),
                    receivedAt, -1);
            return;
        }

        long ackAfter = delay(config.ackDelayMs());
        long finalAfter = ackAfter + delay(config.fillDelayMs());
        scheduler.schedule(() -> send(sessionId, acknowledgement(order)), ackAfter, TimeUnit.MILLISECONDS);
        scheduler.schedule(() -> {
            FakeLpOutcomes.Decision decision;
            synchronized (this) {
                decision = FakeLpOutcomes.decide(order.terms, nextMarketPrice(), random.nextDouble() * 100, config);
            }
            sendFinal(sessionId, order, decision, receivedAt, ackAfter);
        }, finalAfter, TimeUnit.MILLISECONDS);
    }

    private void sendFinal(SessionID sessionId, LiveOrder order, FakeLpOutcomes.Decision decision,
                           long receivedAt, long ackAfter) {
        BigDecimal qty = order.terms.qty();
        boolean fix44 = config.profile() == FakeLpConfig.Profile.FINALTO;
        switch (decision.kind()) {
            case FILL -> send(sessionId, report(order, fix44 ? ExecType.TRADE : ExecType.FILL, OrdStatus.FILLED,
                    qty, decision.price(), qty, decision.price(), BigDecimal.ZERO, null));
            case PARTIAL -> {
                BigDecimal filled = decision.filledQty();
                send(sessionId, report(order, fix44 ? ExecType.TRADE : ExecType.PARTIAL_FILL,
                        OrdStatus.PARTIALLY_FILLED, filled, decision.price(), filled, decision.price(),
                        qty.subtract(filled), null));
                // IOC: the venue cancels whatever did not trade, which closes the order.
                send(sessionId, report(order, ExecType.CANCELED, OrdStatus.CANCELED, null, null, filled,
                        decision.price(), BigDecimal.ZERO, "Fake LP: remainder cancelled (IOC)"));
            }
            case NO_FILL -> send(sessionId, report(order, ExecType.CANCELED, OrdStatus.CANCELED, null, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, decision.text()));
            case REJECT -> {
                Message rejected = report(order, ExecType.REJECTED, OrdStatus.REJECTED, null, null,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, decision.text());
                rejected.setInt(OrdRejReason.FIELD, OrdRejReason.OTHER);
                send(sessionId, rejected);
            }
        }
        long finalAfter = System.currentTimeMillis() - receivedAt;
        stats.finished(decision.kind(), finalAfter);
        writeReport(receivedAt, sessionId, order, decision, ackAfter, finalAfter);
    }

    private Message acknowledgement(LiveOrder order) {
        return report(order, ExecType.PENDING_NEW, OrdStatus.PENDING_NEW, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, order.terms.qty(), null);
    }

    private Message report(LiveOrder order, char execType, char ordStatus, BigDecimal lastQty, BigDecimal lastPx,
                           BigDecimal cumQty, BigDecimal avgPx, BigDecimal leavesQty, String text) {
        Message report = new Message();
        report.getHeader().setString(MsgType.FIELD, MsgType.EXECUTION_REPORT);
        report.setString(OrderID.FIELD, order.orderId);
        report.setString(ClOrdID.FIELD, order.clOrdId);
        report.setString(ExecID.FIELD, "FAKE-EX-" + execSeq.incrementAndGet());
        report.setChar(ExecType.FIELD, execType);
        report.setChar(OrdStatus.FIELD, ordStatus);
        report.setString(Symbol.FIELD, order.symbol);
        report.setChar(Side.FIELD, order.side);
        report.setChar(OrdType.FIELD, order.ordType);
        report.setDecimal(OrderQty.FIELD, order.terms.qty());
        if (order.terms.limit()) {
            report.setDecimal(Price.FIELD, order.terms.limitPrice());
        }
        if (lastQty != null && lastQty.signum() > 0) {
            report.setDecimal(LastQty.FIELD, lastQty);
            report.setDecimal(LastPx.FIELD, lastPx);
        }
        report.setDecimal(LeavesQty.FIELD, leavesQty);
        report.setDecimal(CumQty.FIELD, cumQty);
        report.setDecimal(AvgPx.FIELD, avgPx == null ? BigDecimal.ZERO : avgPx);
        report.setUtcTimeStamp(TransactTime.FIELD, LocalDateTime.now(ZoneOffset.UTC), true);
        if (order.account != null) {
            report.setString(Account.FIELD, order.account);
        }
        if (text != null) {
            report.setString(Text.FIELD, text);
        }
        return report;
    }

    private void send(SessionID sessionId, Message message) {
        try {
            Session.sendToTarget(message, sessionId);
        } catch (SessionNotFound e) {
            System.out.println("[fake-lp] WARN session gone before the report was sent: " + sessionId);
        }
    }

    /** A small random walk, so limit orders near the market sometimes trade and sometimes do not. */
    private BigDecimal nextMarketPrice() {
        BigDecimal move = config.priceStep().multiply(BigDecimal.valueOf(random.nextInt(3) - 1));
        marketPrice = marketPrice.add(move).max(config.priceStep());
        return marketPrice.setScale(Math.max(2, config.priceStep().scale()), RoundingMode.HALF_UP);
    }

    private long delay(long base) {
        if (config.jitterMs() <= 0) {
            return base;
        }
        long jitter;
        synchronized (this) {
            jitter = (long) (random.nextDouble() * (2 * config.jitterMs() + 1)) - config.jitterMs();
        }
        return Math.max(0, base + jitter);
    }

    private synchronized boolean throttled(long now) {
        if (config.maxOrdersPerSecond() <= 0) {
            return false;
        }
        long second = now / 1000;
        if (second != throttleSecond) {
            throttleSecond = second;
            ordersThisSecond = 0;
        }
        return ++ordersThisSecond > config.maxOrdersPerSecond();
    }

    // ---------------------------------------------------------------- report file

    private static BufferedWriter openReport(FakeLpConfig config) {
        if (config.reportFile() == null) {
            return null;
        }
        try {
            BufferedWriter writer = Files.newBufferedWriter(config.reportFile());
            writer.write(CSV_HEADER);
            writer.newLine();
            return writer;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the fake LP report " + config.reportFile(), e);
        }
    }

    private synchronized void writeReport(long receivedAt, SessionID sessionId, LiveOrder order,
                                          FakeLpOutcomes.Decision decision, long ackAfter, long finalAfter) {
        if (report == null) {
            return;
        }
        try {
            report.write(String.join(",",
                    String.valueOf(receivedAt),
                    sessionId.getSenderCompID() + "/" + sessionId.getTargetCompID(),
                    csv(order.clOrdId),
                    order.terms.buy() ? "BUY" : "SELL",
                    order.terms.limit() ? "LIMIT" : "MARKET",
                    order.terms.qty().toPlainString(),
                    order.terms.limit() ? order.terms.limitPrice().toPlainString() : "",
                    decision.kind().name(),
                    decision.filledQty().toPlainString(),
                    decision.price() == null ? "" : decision.price().toPlainString(),
                    String.valueOf(ackAfter),
                    String.valueOf(finalAfter),
                    csv(decision.text() == null ? "" : decision.text())));
            report.newLine();
            if (stats.finishedCount() % 100 == 0) {
                report.flush();
            }
        } catch (IOException e) {
            System.out.println("[fake-lp] WARN report write failed: " + e.getMessage());
        }
    }

    private synchronized void closeReport() {
        if (report == null) {
            return;
        }
        try {
            report.close();
        } catch (IOException e) {
            System.out.println("[fake-lp] WARN report close failed: " + e.getMessage());
        }
    }

    private static String csv(String value) {
        return value.contains(",") || value.contains("\"") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isType(Message message, String msgType) {
        try {
            return msgType.equals(message.getHeader().getString(MsgType.FIELD));
        } catch (FieldNotFound e) {
            return false;
        }
    }

    private static String stringOrNull(Message message, int tag) {
        try {
            return message.isSetField(tag) ? message.getString(tag) : null;
        } catch (FieldNotFound e) {
            return null;
        }
    }

    /** The fields of a NewOrderSingle the reports echo back. */
    private record LiveOrder(String orderId, String clOrdId, String symbol, char side, char ordType,
                             String account, FakeLpOutcomes.Order terms) {

        static LiveOrder from(Message message, String orderId) throws FieldNotFound {
            char side = message.getChar(Side.FIELD);
            char ordType = message.getChar(OrdType.FIELD);
            boolean limit = ordType == OrdType.LIMIT;
            BigDecimal price = limit ? message.getDecimal(Price.FIELD) : null;
            return new LiveOrder(orderId,
                    message.getString(ClOrdID.FIELD),
                    message.getString(Symbol.FIELD),
                    side,
                    ordType,
                    stringOrNull(message, Account.FIELD),
                    new FakeLpOutcomes.Order(side == Side.BUY, limit, price, message.getDecimal(OrderQty.FIELD)));
        }
    }
}
