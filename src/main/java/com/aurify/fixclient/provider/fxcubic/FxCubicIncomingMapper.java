package com.aurify.fixclient.provider.fxcubic;

import com.aurify.fixclient.canonical.enums.CanonicalExecType;
import com.aurify.fixclient.canonical.enums.CanonicalOrdStatus;
import com.aurify.fixclient.canonical.enums.CanonicalSide;
import com.aurify.fixclient.canonical.event.CanonicalEvent;
import com.aurify.fixclient.canonical.event.CanonicalExecutionReport;
import com.aurify.fixclient.canonical.event.CanonicalQuote;
import com.aurify.fixclient.canonical.event.CanonicalReject;
import org.springframework.stereotype.Component;
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

/** Converts FXCubic FIX 4.3 application messages into canonical DTOs.
 *  This is the ONLY place FXCubic-specific field semantics are interpreted.
 *
 *  Every message type the cracker routes must map to something: an unmapped
 *  reject would otherwise be swallowed here and leave the caller waiting for a
 *  reply that is never coming. */
@Component
public class FxCubicIncomingMapper {

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
        throw new IllegalArgumentException("Unhandled FXCubic message type: " + message.getClass());
    }

    private CanonicalExecutionReport mapExecutionReport(ExecutionReport report) throws FieldNotFound {
        return CanonicalExecutionReport.builder()
                .provider(FxCubicProviderAdapter.PROVIDER_NAME)
                .orderId(report.getOrderID().getValue())
                .clOrdId(report.getClOrdID().getValue())
                .execId(report.getExecID().getValue())
                .execType(mapExecType(report.getExecType().getValue()))
                .ordStatus(mapOrdStatus(report.getOrdStatus().getValue()))
                .symbol(report.getSymbol().getValue())
                .side(report.getSide().getValue() == Side.BUY ? CanonicalSide.BUY : CanonicalSide.SELL)
                .orderQty(BigDecimal.valueOf(report.getOrderQty().getValue()))
                .price(report.isSetPrice() ? BigDecimal.valueOf(report.getPrice().getValue()) : null)
                .lastQty(report.isSetLastQty() ? BigDecimal.valueOf(report.getLastQty().getValue()) : null)
                .lastPx(report.isSetLastPx() ? BigDecimal.valueOf(report.getLastPx().getValue()) : null)
                .leavesQty(BigDecimal.valueOf(report.getLeavesQty().getValue()))
                .cumQty(BigDecimal.valueOf(report.getCumQty().getValue()))
                .avgPx(BigDecimal.valueOf(report.getAvgPx().getValue()))
                .rejectText(report.isSetText() ? report.getText().getValue() : null)
                .ordRejReason(report.isSetOrdRejReason() ? report.getOrdRejReason().getValue() : null)
                .secondaryClOrdId(report.isSetField(526) ? report.getString(526) : null)
                .occurredAt(Instant.now())
                .build();
    }

    /** 35=9. Refers to an order by ClOrdID, so a pending caller can be failed. */
    private CanonicalReject mapOrderCancelReject(OrderCancelReject reject) throws FieldNotFound {
        return CanonicalReject.builder()
                .provider(FxCubicProviderAdapter.PROVIDER_NAME)
                .refId(reject.getClOrdID().getValue())
                .reasonCode(reject.isSetCxlRejReason()
                        ? String.valueOf(reject.getCxlRejReason().getValue()) : null)
                .text(reject.isSetText() ? reject.getText().getValue() : null)
                .occurredAt(Instant.now())
                .build();
    }

    /** 35=j. BusinessRejectRefID (379) echoes the ClOrdID of the rejected message. */
    private CanonicalReject mapBusinessMessageReject(BusinessMessageReject reject) throws FieldNotFound {
        return CanonicalReject.builder()
                .provider(FxCubicProviderAdapter.PROVIDER_NAME)
                .refId(reject.isSetBusinessRejectRefID() ? reject.getBusinessRejectRefID().getValue() : null)
                .reasonCode(String.valueOf(reject.getBusinessRejectReason().getValue()))
                .text(reject.isSetText() ? reject.getText().getValue() : null)
                .occurredAt(Instant.now())
                .build();
    }

    /** 35=3. Session-level: identifies a sequence number, not an order, so no
     *  pending call can be matched - it is surfaced as an event for operators. */
    private CanonicalReject mapSessionReject(Reject reject) throws FieldNotFound {
        return CanonicalReject.builder()
                .provider(FxCubicProviderAdapter.PROVIDER_NAME)
                .refId(reject.isSetRefSeqNum() ? "SeqNum:" + reject.getRefSeqNum().getValue() : null)
                .reasonCode(reject.isSetSessionRejectReason()
                        ? String.valueOf(reject.getSessionRejectReason().getValue()) : null)
                .text(reject.isSetText() ? reject.getText().getValue() : null)
                .occurredAt(Instant.now())
                .build();
    }

    private CanonicalReject mapMarketDataReject(MarketDataRequestReject reject) throws FieldNotFound {
        return CanonicalReject.builder()
                .provider(FxCubicProviderAdapter.PROVIDER_NAME)
                .refId(reject.getMDReqID().getValue())
                // MDReqRejReason: 0=Unknown symbol, 1=Duplicate MDReqID
                .reasonCode(reject.isSetMDReqRejReason() ? String.valueOf(reject.getMDReqRejReason().getValue()) : null)
                .text(reject.isSetText() ? reject.getText().getValue() : null)
                .occurredAt(Instant.now())
                .build();
    }

    /** 35=W: the LP's full top-of-book, per FXCubic spec one symbol per request
     *  so one snapshot answers one subscription. Bid/Offer entries missing from
     *  the group stay null - a one-sided market is legitimate, not an error. */
    private CanonicalQuote mapMarketDataSnapshot(MarketDataSnapshotFullRefresh snapshot) throws FieldNotFound {
        String symbol = snapshot.isSetSymbol() ? snapshot.getSymbol().getValue() : null;
        BigDecimal bid = null, bidSize = null, ask = null, askSize = null;
        int count = snapshot.getNoMDEntries().getValue();
        for (int i = 1; i <= count; i++) {
            MarketDataSnapshotFullRefresh.NoMDEntries entry = new MarketDataSnapshotFullRefresh.NoMDEntries();
            snapshot.getGroup(i, entry);
            BigDecimal px = entry.isSetMDEntryPx() ? BigDecimal.valueOf(entry.getMDEntryPx().getValue()) : null;
            BigDecimal size = entry.isSetMDEntrySize() ? BigDecimal.valueOf(entry.getMDEntrySize().getValue()) : null;
            if (entry.getMDEntryType().getValue() == MDEntryType.BID) {
                bid = px; bidSize = size;
            } else if (entry.getMDEntryType().getValue() == MDEntryType.OFFER) {
                ask = px; askSize = size;
            }
        }

        return CanonicalQuote.builder()
                .provider(FxCubicProviderAdapter.PROVIDER_NAME)
                .symbol(symbol)
                .bid(bid).bidSize(bidSize).ask(ask).askSize(askSize)
                .quoteId(snapshot.isSetMDReqID() ? snapshot.getMDReqID().getValue() : null)
                .occurredAt(Instant.now())
                .build();
    }

    /**
     * 35=X: one or more entry-level updates. A DELETE(2) carries no usable
     * price - the entry simply stops being quoted, and the next snapshot or
     * update corrects the picture - so it is skipped rather than nulling out a
     * side the venue never actually withdrew from.
     */
    private CanonicalQuote mapMarketDataIncremental(MarketDataIncrementalRefresh incremental) throws FieldNotFound {
        String[] symbolHolder = new String[1];
        int count = incremental.getNoMDEntries().getValue();
        BigDecimal bid = null, bidSize = null, ask = null, askSize = null;
        for (int i = 1; i <= count; i++) {
            MarketDataIncrementalRefresh.NoMDEntries group = new MarketDataIncrementalRefresh.NoMDEntries();
            incremental.getGroup(i, group);
            if (group.isSetMDUpdateAction() && group.getMDUpdateAction().getValue() == MDUpdateAction.DELETE) {
                continue;
            }
            if (group.isSetSymbol()) {
                symbolHolder[0] = group.getSymbol().getValue();
            }
            BigDecimal px = group.isSetMDEntryPx() ? BigDecimal.valueOf(group.getMDEntryPx().getValue()) : null;
            BigDecimal size = group.isSetMDEntrySize() ? BigDecimal.valueOf(group.getMDEntrySize().getValue()) : null;
            if (group.getMDEntryType().getValue() == MDEntryType.BID) {
                bid = px; bidSize = size;
            } else if (group.getMDEntryType().getValue() == MDEntryType.OFFER) {
                ask = px; askSize = size;
            }
        }

        return CanonicalQuote.builder()
                .provider(FxCubicProviderAdapter.PROVIDER_NAME)
                .symbol(symbolHolder[0])
                .bid(bid).bidSize(bidSize).ask(ask).askSize(askSize)
                .occurredAt(Instant.now())
                .build();
    }

    private CanonicalExecType mapExecType(char execType) {
        return switch (execType) {
            case ExecType.NEW -> CanonicalExecType.NEW;
            case ExecType.PENDING_NEW -> CanonicalExecType.PENDING_NEW;
            case ExecType.FILL -> CanonicalExecType.FILL;
            case ExecType.REJECTED -> CanonicalExecType.REJECTED;
            default -> CanonicalExecType.UNKNOWN;
        };
    }

    private CanonicalOrdStatus mapOrdStatus(char ordStatus) {
        return switch (ordStatus) {
            case OrdStatus.NEW -> CanonicalOrdStatus.NEW;
            case OrdStatus.PENDING_NEW -> CanonicalOrdStatus.PENDING_NEW;
            case OrdStatus.FILLED -> CanonicalOrdStatus.FILLED;
            case OrdStatus.REJECTED -> CanonicalOrdStatus.REJECTED;
            default -> CanonicalOrdStatus.UNKNOWN;
        };
    }
}
