package com.aurify.fixclient.grpc;

import aurify.lphedge.v1.ExecutionReport;
import com.aurify.fixclient.canonical.enums.CanonicalOrdStatus;
import com.aurify.fixclient.canonical.event.CanonicalExecutionReport;
import com.aurify.fixclient.journal.JournaledReport;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Canonical execution report to its wire form.
 *
 * The boundary rule that keeps generated protobuf types out of the rest of the
 * gateway, the same way {@code LpSessionSpecMapper} keeps them out on the way
 * in and {@code transport/} keeps raw QuickFIX messages out.
 */
final class ExecutionReportMapper {

    private ExecutionReportMapper() {}

    static ExecutionReport toProto(JournaledReport journaled) {
        CanonicalExecutionReport report = journaled.report();
        return ExecutionReport.newBuilder()
                .setLpAccountId(nullToEmpty(journaled.lpAccountId()))
                .setRawFix(nullToEmpty(journaled.rawFix()))
                .setProvider(nullToEmpty(report.getProvider()))
                .setClOrdId(nullToEmpty(report.getClOrdId()))
                .setOrderId(nullToEmpty(report.getOrderId()))
                .setExecId(nullToEmpty(report.getExecId()))
                .setExecType(report.getExecType() == null ? "" : report.getExecType().name())
                .setOrdStatus(report.getOrdStatus() == null ? "" : report.getOrdStatus().name())
                .setSymbol(nullToEmpty(report.getSymbol()))
                .setSide(report.getSide() == null ? "" : report.getSide().name())
                .setOrderQty(decimal(report.getOrderQty()))
                .setPrice(decimal(report.getPrice()))
                .setLastQty(decimal(report.getLastQty()))
                .setLastPx(decimal(report.getLastPx()))
                .setLeavesQty(decimal(report.getLeavesQty()))
                .setCumQty(decimal(report.getCumQty()))
                .setAvgPx(decimal(report.getAvgPx()))
                .setRejectText(nullToEmpty(report.getRejectText()))
                .setOrdRejReason(report.getOrdRejReason() == null ? 0 : report.getOrdRejReason())
                .setSecondaryClOrdId(nullToEmpty(report.getSecondaryClOrdId()))
                .setOccurredAtEpochMs(epochMs(report.getOccurredAt()))
                .setTerminal(isTerminal(report.getOrdStatus()))
                .build();
    }

    private static boolean isTerminal(CanonicalOrdStatus status) {
        return status == CanonicalOrdStatus.FILLED
                || status == CanonicalOrdStatus.REJECTED
                || status == CanonicalOrdStatus.CANCELLED;
    }

    private static double decimal(BigDecimal value) {
        return value == null ? 0D : value.doubleValue();
    }

    private static long epochMs(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
