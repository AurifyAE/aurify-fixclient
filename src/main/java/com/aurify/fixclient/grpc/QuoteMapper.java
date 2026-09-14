package com.aurify.fixclient.grpc;

import aurify.lphedge.v1.Quote;
import com.aurify.fixclient.canonical.event.CanonicalQuote;
import com.aurify.fixclient.marketdata.LpQuoteRegistry.AttributedQuote;

import java.math.BigDecimal;
import java.time.Instant;

/** Canonical quote to its wire form - the market-data equivalent of
 *  {@code ExecutionReportMapper}. */
final class QuoteMapper {

    private QuoteMapper() {}

    static Quote toProto(AttributedQuote attributed) {
        CanonicalQuote quote = attributed.quote();
        return Quote.newBuilder()
                .setLpAccountId(nullToEmpty(attributed.lpAccountId()))
                .setProvider(nullToEmpty(quote.getProvider()))
                .setSymbol(nullToEmpty(quote.getSymbol()))
                .setBid(decimal(quote.getBid()))
                .setBidSize(decimal(quote.getBidSize()))
                .setAsk(decimal(quote.getAsk()))
                .setAskSize(decimal(quote.getAskSize()))
                .setOccurredAtEpochMs(epochMs(quote.getOccurredAt()))
                .setQuoteId(nullToEmpty(quote.getQuoteId()))
                .build();
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
