package com.aurify.fixclient.provider.finalto;

import com.aurify.fixclient.canonical.enums.CanonicalExecType;
import com.aurify.fixclient.canonical.enums.CanonicalOrdStatus;
import com.aurify.fixclient.canonical.enums.CanonicalSide;
import com.aurify.fixclient.canonical.event.CanonicalEvent;
import com.aurify.fixclient.canonical.event.CanonicalExecutionReport;
import com.aurify.fixclient.canonical.event.CanonicalReject;
import org.springframework.stereotype.Component;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.fix44.BusinessMessageReject;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.MarketDataRequestReject;
import quickfix.fix44.OrderCancelReject;
import quickfix.fix44.Reject;

import java.math.BigDecimal;
import java.time.Instant;

/** Converts Finalto (CFH) FIX 4.4 application messages into canonical DTOs.
 *  This is the ONLY place Finalto-specific field semantics are interpreted -
 *  mirrors {@code FxCubicIncomingMapper}'s structure exactly, over the FIX 4.4
 *  message classes instead of 4.3, since the wire values (ExecType, OrdStatus)
 *  Finalto uses are all standard FIX (spec p.19) and need no enum extension.
 *
 *  Every message type the cracker routes must map to something: an unmapped
 *  reject would otherwise be swallowed here and leave the caller waiting for a
 *  reply that is never coming. */
@Component
public class FinaltoIncomingMapper {

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
        throw new IllegalArgumentException("Unhandled Finalto message type: " + message.getClass());
    }

    private CanonicalExecutionReport mapExecutionReport(ExecutionReport report) throws FieldNotFound {
        return CanonicalExecutionReport.builder()
                .provider(FinaltoProviderAdapter.PROVIDER_NAME)
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
                .provider(FinaltoProviderAdapter.PROVIDER_NAME)
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
                .provider(FinaltoProviderAdapter.PROVIDER_NAME)
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
                .provider(FinaltoProviderAdapter.PROVIDER_NAME)
                .refId(reject.isSetRefSeqNum() ? "SeqNum:" + reject.getRefSeqNum().getValue() : null)
                .reasonCode(reject.isSetSessionRejectReason()
                        ? String.valueOf(reject.getSessionRejectReason().getValue()) : null)
                .text(reject.isSetText() ? reject.getText().getValue() : null)
                .occurredAt(Instant.now())
                .build();
    }

    private CanonicalReject mapMarketDataReject(MarketDataRequestReject reject) throws FieldNotFound {
        return CanonicalReject.builder()
                .provider(FinaltoProviderAdapter.PROVIDER_NAME)
                .refId(reject.getMDReqID().getValue())
                .reasonCode(reject.isSetMDReqRejReason() ? String.valueOf(reject.getMDReqRejReason().getValue()) : null)
                .text(reject.isSetText() ? reject.getText().getValue() : null)
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
