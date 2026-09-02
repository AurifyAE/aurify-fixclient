package com.aurify.fixclient.session;

import com.aurify.fixclient.provider.OutboundPolicy;

import java.util.Objects;
import java.util.Set;

/**
 * Everything the gateway needs to talk to one LP account, supplied by the
 * caller on every request. The gateway keeps no LP configuration of its own -
 * this record is the entire contract.
 *
 * WARNING: carries a FIX password. toString() redacts it; never log the raw
 * fields, and never place credentials into a FIX application message.
 */
public record LpSessionSpec(
        String lpAccountId,
        String provider,
        String fixVersion,
        FixSessionSpec trading,
        Set<String> allowedSymbols,
        long maxOrderSize,
        double maxPositionPerSymbol,
        double maxTotalExposure,
        String specFingerprint,
        String account
) {

    public LpSessionSpec {
        lpAccountId = requireText(lpAccountId, "lpAccountId");
        provider = requireText(provider, "provider").toLowerCase();
        fixVersion = blankTo(fixVersion, "FIX.4.3");
        Objects.requireNonNull(trading, "trading session is required");
        allowedSymbols = allowedSymbols == null ? Set.of() : Set.copyOf(allowedSymbols);
        specFingerprint = blankTo(specFingerprint, "");
        account = blankTo(account, "");
    }

    /** The LP-side account for FIX tag 1, or null when the caller set none. */
    public String accountOrNull() {
        return account.isEmpty() ? null : account;
    }

    /** The subset an adapter is allowed to see: limits and symbols, no credentials. */
    public OutboundPolicy policy() {
        return new OutboundPolicy(allowedSymbols, maxOrderSize, maxPositionPerSymbol, maxTotalExposure);
    }

    @Override
    public String toString() {
        return "LpSessionSpec[lpAccountId=" + lpAccountId
                + ", provider=" + provider
                + ", fixVersion=" + fixVersion
                + ", trading=" + trading
                + ", allowedSymbols=" + allowedSymbols
                + ", maxOrderSize=" + maxOrderSize
                + ", account=" + account
                + ", fingerprint=" + specFingerprint + "]";
    }

    /** One FIX session's connection parameters. */
    public record FixSessionSpec(
            String host,
            int port,
            String senderCompId,
            String targetCompId,
            String username,
            String password,
            boolean useSsl,
            boolean resetSeqNumOnLogon,
            int heartbeatIntervalSeconds,
            String serverName,
            String startTime,
            String endTime
    ) {
        public FixSessionSpec {
            host = requireText(host, "host");
            senderCompId = requireText(senderCompId, "senderCompId");
            targetCompId = requireText(targetCompId, "targetCompId");
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
            heartbeatIntervalSeconds = heartbeatIntervalSeconds > 0 ? heartbeatIntervalSeconds : 30;
            startTime = blankTo(startTime, "00:00:00");
            endTime = blankTo(endTime, "23:59:59");
        }

        /** Redacts the password - this is what ends up in logs. */
        @Override
        public String toString() {
            return "FixSessionSpec[" + host + ":" + port
                    + ", sender=" + senderCompId
                    + ", target=" + targetCompId
                    + ", username=" + username
                    + ", password=" + (password == null || password.isEmpty() ? "<none>" : "***")
                    + ", ssl=" + useSsl
                    + ", resetSeqNum=" + resetSeqNumOnLogon
                    + ", heartBtInt=" + heartbeatIntervalSeconds + "]";
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
