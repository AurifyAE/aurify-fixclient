package com.aurify.fixclient.provider.stonex;

import com.aurify.fixclient.canonical.enums.CanonicalExecType;
import com.aurify.fixclient.canonical.enums.CanonicalOrdStatus;
import com.aurify.fixclient.canonical.enums.CanonicalSide;
import com.aurify.fixclient.canonical.event.CanonicalEvent;
import com.aurify.fixclient.canonical.event.CanonicalExecutionReport;
import com.aurify.fixclient.canonical.event.CanonicalQuote;
import com.aurify.fixclient.canonical.event.CanonicalReject;
import org.springframework.stereotype.Component;
import quickfix.FieldMap;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.fix43.BusinessMessageReject;
import quickfix.fix43.ExecutionReport;
import quickfix.fix43.MarketDataIncrementalRefresh;
import quickfix.fix43.MarketDataRequestReject;
import quickfix.fix43.MarketDataSnapshotFullRefresh;
import quickfix.fix43.OrderCancelReject;
import quickfix.fix43.Reject;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Converts StoneX (Integral FX Inside, FIX 4.3) application messages into
 * canonical DTOs. Written from the FX Inside FIX Client API 5.1 spec, not
 * copied from another venue's mapper - the README explains why that matters.
 *
 * Every message type the cracker routes maps to something: an unmapped reject
 * would leave the caller waiting for a reply that is never coming.
 */
@Component
public class StoneXIncomingMapper {

    public CanonicalEvent map(Message message, SessionID sessionId) throws FieldNotFound {
        if (message instanceof ExecutionReport report) {
            return mapExecutionReport(report);
        }
        if (message instanceof OrderCancelReject reject) {
            return mapOrderCancelReject(reject);
        }
        if (message instanceof BusinessMessageReject reject) {
            return mapBusinessMessageReject(reject);
        }
        if (message instanceof Reject reject) {
            return mapSessionReject(reject);
        }
        if (message instanceof MarketDataRequestReject reject) {
            return mapMarketDataReject(reject);
        }
        if (message instanceof MarketDataSnapshotFullRefresh snapshot) {
            return mapMarketDataSnapshot(snapshot);
        }
        if (message instanceof MarketDataIncrementalRefresh incremental) {
            return mapMarketDataIncremental(incremental);
        }
        throw new IllegalArgumentException("Unhandled StoneX message type: " + message.getClass());
    }

    /**
     * Quantities are read from the wire string, not through a double, so a
     * fractional ounce fill (e.g. 3.21) is reported exactly. A quantity the
     * venue left out reads as zero rather than failing the whole report -
     * a rejection must still reach the waiting caller.
     */
    private CanonicalExecutionReport mapExecutionReport(ExecutionReport report) throws FieldNotFound {
        return CanonicalExecutionReport.builder()
                .provider(StoneXProviderAdapter.PROVIDER_NAME)
                .orderId(stringOrNull(report, OrderID.FIELD))
                .clOrdId(report.getClOrdID().getValue())
                .execId(stringOrNull(report, ExecID.FIELD))
                .execType(mapExecType(report.getExecType().getValue()))
                .ordStatus(mapOrdStatus(report.getOrdStatus().getValue()))
                .symbol(stringOrNull(report, Symbol.FIELD))
                .side(report.isSetSide() && report.getSide().getValue() == Side.SELL
                        ? CanonicalSide.SELL : CanonicalSide.BUY)
                .orderQty(decimalOr(report, OrderQty.FIELD, BigDecimal.ZERO))
                .price(decimalOr(report, Price.FIELD, null))
                .lastQty(decimalOr(report, LastQty.FIELD, null))
                .lastPx(decimalOr(report, LastPx.FIELD, null))
                .leavesQty(decimalOr(report, LeavesQty.FIELD, BigDecimal.ZERO))
                .cumQty(decimalOr(report, CumQty.FIELD, BigDecimal.ZERO))
                .avgPx(decimalOr(report, AvgPx.FIELD, BigDecimal.ZERO))
                .rejectText(stringOrNull(report, Text.FIELD))
                .ordRejReason(report.isSetOrdRejReason() ? report.getOrdRejReason().getValue() : null)
                .secondaryClOrdId(stringOrNull(report, SecondaryClOrdID.FIELD))
                .occurredAt(Instant.now())
                .build();
    }

    /** 35=9. Refers to an order by ClOrdID, so a pending caller can be failed. */
    private CanonicalReject mapOrderCancelReject(OrderCancelReject reject) throws FieldNotFound {
        return CanonicalReject.builder()
                .provider(StoneXProviderAdapter.PROVIDER_NAME)
                .refId(reject.getClOrdID().getValue())
                .reasonCode(stringOrNull(reject, CxlRejReason.FIELD))
                .text(stringOrNull(reject, Text.FIELD))
                .occurredAt(Instant.now())
                .build();
    }

    /** 35=j. BusinessRejectRefID (379) echoes the ClOrdID of the rejected message. */
    private CanonicalReject mapBusinessMessageReject(BusinessMessageReject reject) throws FieldNotFound {
        return CanonicalReject.builder()
                .provider(StoneXProviderAdapter.PROVIDER_NAME)
                .refId(stringOrNull(reject, BusinessRejectRefID.FIELD))
                .reasonCode(stringOrNull(reject, BusinessRejectReason.FIELD))
                .text(stringOrNull(reject, Text.FIELD))
                .occurredAt(Instant.now())
                .build();
    }

    /** 35=3. Session-level: identifies a sequence number, not an order. */
    private CanonicalReject mapSessionReject(Reject reject) throws FieldNotFound {
        return CanonicalReject.builder()
                .provider(StoneXProviderAdapter.PROVIDER_NAME)
                .refId(reject.isSetRefSeqNum() ? "SeqNum:" + reject.getRefSeqNum().getValue() : null)
                .reasonCode(stringOrNull(reject, SessionRejectReason.FIELD))
                .text(stringOrNull(reject, Text.FIELD))
                .occurredAt(Instant.now())
                .build();
    }

    /** 35=Y (3.3.3). MDReqRejReason 3 = not permissioned for the pair. */
    private CanonicalReject mapMarketDataReject(MarketDataRequestReject reject) throws FieldNotFound {
        return CanonicalReject.builder()
                .provider(StoneXProviderAdapter.PROVIDER_NAME)
                .refId(reject.getMDReqID().getValue())
                .reasonCode(stringOrNull(reject, MDReqRejReason.FIELD))
                .text(stringOrNull(reject, Text.FIELD))
                .occurredAt(Instant.now())
                .build();
    }

    /**
     * 35=W (3.3.2). Symbol arrives as "XAU/USD". One message may carry several
     * rates per side (tiers, or one per provider); the best tradable price per
     * side wins. Per the spec, an entry whose price or size is 0 must be
     * ignored, and QuoteCondition(276)=B marks a stream that is not tradable.
     */
    private CanonicalQuote mapMarketDataSnapshot(MarketDataSnapshotFullRefresh snapshot) throws FieldNotFound {
        Book book = new Book();
        int count = snapshot.getNoMDEntries().getValue();
        for (int i = 1; i <= count; i++) {
            MarketDataSnapshotFullRefresh.NoMDEntries entry = new MarketDataSnapshotFullRefresh.NoMDEntries();
            snapshot.getGroup(i, entry);
            book.offer(entry);
        }
        return book.toQuote(stringOrNull(snapshot, Symbol.FIELD), stringOrNull(snapshot, MDReqID.FIELD));
    }

    /** 35=X. The spec documents only full refreshes; mapped anyway so an
     *  unexpected incremental yields a quote instead of an error. */
    private CanonicalQuote mapMarketDataIncremental(MarketDataIncrementalRefresh incremental) throws FieldNotFound {
        Book book = new Book();
        String symbol = null;
        int count = incremental.getNoMDEntries().getValue();
        for (int i = 1; i <= count; i++) {
            MarketDataIncrementalRefresh.NoMDEntries entry = new MarketDataIncrementalRefresh.NoMDEntries();
            incremental.getGroup(i, entry);
            if (entry.isSetMDUpdateAction() && entry.getMDUpdateAction().getValue() == MDUpdateAction.DELETE) {
                continue;
            }
            if (symbol == null) {
                symbol = stringOrNull(entry, Symbol.FIELD);
            }
            book.offer(entry);
        }
        return book.toQuote(symbol, stringOrNull(incremental, MDReqID.FIELD));
    }

    /**
     * ExecType per FX Inside 4.3.10 / Table 1-4. A fill arrives as {@code F}
     * (Trade) - the "New" ack may be skipped entirely (p.36) - so F must map
     * to FILL; the FIX 4.2 spellings 1/2 are tolerated. {@code 7} (Stopped)
     * is how FX Inside reports an order it refused to accept. {@code D}
     * (Restated) and {@code 6}/{@code E} (pending) carry no outcome.
     */
    static CanonicalExecType mapExecType(char execType) {
        return switch (execType) {
            case ExecType.NEW -> CanonicalExecType.NEW;
            case ExecType.PENDING_NEW -> CanonicalExecType.PENDING_NEW;
            case ExecType.TRADE, ExecType.FILL, ExecType.PARTIAL_FILL -> CanonicalExecType.FILL;
            case ExecType.CANCELED, ExecType.EXPIRED -> CanonicalExecType.CANCELLED;
            case ExecType.REJECTED, ExecType.STOPPED -> CanonicalExecType.REJECTED;
            default -> CanonicalExecType.UNKNOWN;
        };
    }

    /**
     * OrdStatus per Table 1-4 (p.37). This decides whether a pending hedge
     * call can answer, since PendingOrderRegistry completes only on
     * FILLED/REJECTED/CANCELLED:
     *  - {@code 7} Stopped: "the server failed to confirm the validity of the
     *    order and the order submission has failed" - a rejection, and
     *    terminal. Left UNKNOWN, a refused order would time the caller out.
     *  - {@code 4} Cancelled: the normal end of an unfilled FOK or of the
     *    unfilled rest of an IOC (p.31), after any partial fill ({@code 1}).
     *  - {@code C} Expired: a GTD order's end - terminal, nothing filled after.
     * {@code 6} (Pending Cancel) stays unmapped on purpose: it is in flight.
     */
    static CanonicalOrdStatus mapOrdStatus(char ordStatus) {
        return switch (ordStatus) {
            case OrdStatus.NEW -> CanonicalOrdStatus.NEW;
            case OrdStatus.PENDING_NEW -> CanonicalOrdStatus.PENDING_NEW;
            case OrdStatus.PARTIALLY_FILLED -> CanonicalOrdStatus.PARTIALLY_FILLED;
            case OrdStatus.FILLED -> CanonicalOrdStatus.FILLED;
            case OrdStatus.CANCELED, OrdStatus.EXPIRED -> CanonicalOrdStatus.CANCELLED;
            case OrdStatus.REJECTED, OrdStatus.STOPPED -> CanonicalOrdStatus.REJECTED;
            default -> CanonicalOrdStatus.UNKNOWN;
        };
    }

    /** Best tradable bid (highest) and offer (lowest) across a message's entries. */
    private static final class Book {
        private BigDecimal bid, bidSize, ask, askSize;

        void offer(FieldMap entry) throws FieldNotFound {
            BigDecimal px = decimalOr(entry, MDEntryPx.FIELD, null);
            BigDecimal size = decimalOr(entry, MDEntrySize.FIELD, null);
            if (px == null || px.signum() == 0 || (size != null && size.signum() == 0)) {
                return; // spec: a zero price or size means "ignore this quote"
            }
            String condition = stringOrNull(entry, QuoteCondition.FIELD);
            if (condition != null && condition.contains("B")) {
                return; // B = Closed/Inactive: last quote is not tradable
            }
            char type = entry.getChar(MDEntryType.FIELD);
            if (type == MDEntryType.BID && (bid == null || px.compareTo(bid) > 0)) {
                bid = px;
                bidSize = size;
            } else if (type == MDEntryType.OFFER && (ask == null || px.compareTo(ask) < 0)) {
                ask = px;
                askSize = size;
            }
        }

        CanonicalQuote toQuote(String symbol, String mdReqId) {
            return CanonicalQuote.builder()
                    .provider(StoneXProviderAdapter.PROVIDER_NAME)
                    .symbol(symbol)
                    .bid(bid).bidSize(bidSize).ask(ask).askSize(askSize)
                    .quoteId(mdReqId)
                    .occurredAt(Instant.now())
                    .build();
        }
    }

    private static String stringOrNull(FieldMap map, int tag) throws FieldNotFound {
        return map.isSetField(tag) ? map.getString(tag) : null;
    }

    private static BigDecimal decimalOr(FieldMap map, int tag, BigDecimal fallback) throws FieldNotFound {
        if (!map.isSetField(tag)) {
            return fallback;
        }
        String raw = map.getString(tag).trim();
        return raw.isEmpty() ? fallback : new BigDecimal(raw);
    }
}
