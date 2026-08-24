package com.aurify.fixclient.provider.fxcubic;

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
import quickfix.fix43.ExecutionReport;
import quickfix.fix43.MarketDataRequestReject;

import java.math.BigDecimal;
import java.time.Instant;

/** Converts FXCubic FIX 4.3 application messages into canonical DTOs.
 *  This is the ONLY place FXCubic-specific field semantics are interpreted. */
@Component
public class FxCubicIncomingMapper {

    public CanonicalEvent map(Message message, SessionID sessionId) throws FieldNotFound {
        if (message instanceof ExecutionReport report) {
            return mapExecutionReport(report);
        }
        if (message instanceof MarketDataRequestReject reject) {
            return mapMarketDataReject(reject);
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
