package com.aurify.fixclient.admin.dto;

import com.aurify.fixclient.session.LpSessionSpec;

import java.util.List;
import java.util.Set;

/**
 * JSON body for the admin session endpoints - the same LpSessionSpec the gRPC
 * caller sends, in a shape Postman/curl can post.
 *
 * This carries a FIX password over the admin HTTP port, which has no auth in
 * front of it. It exists for local testing; do not expose the admin port.
 */
public record EnsureSessionRequest(
        String lpAccountId,
        String provider,
        String fixVersion,
        TradingSession trading,
        List<String> allowedSymbols,
        Long maxOrderSize,
        Double maxPositionPerSymbol,
        Double maxTotalExposure,
        String specFingerprint,
        String account
) {

    public record TradingSession(
            String host,
            Integer port,
            String senderCompId,
            String targetCompId,
            String username,
            String password,
            Boolean useSsl,
            Boolean resetSeqNumOnLogon,
            Integer heartbeatIntervalSeconds,
            String serverName,
            String startTime,
            String endTime
    ) {}

    public LpSessionSpec toSpec() {
        if (trading == null) {
            throw new IllegalArgumentException("trading session is required");
        }

        LpSessionSpec.FixSessionSpec session = new LpSessionSpec.FixSessionSpec(
                trading.host(),
                trading.port() == null ? 0 : trading.port(),
                trading.senderCompId(),
                trading.targetCompId(),
                trading.username(),
                trading.password(),
                Boolean.TRUE.equals(trading.useSsl()),
                Boolean.TRUE.equals(trading.resetSeqNumOnLogon()),
                trading.heartbeatIntervalSeconds() == null ? 0 : trading.heartbeatIntervalSeconds(),
                trading.serverName(),
                trading.startTime(),
                trading.endTime());

        return new LpSessionSpec(
                lpAccountId,
                provider == null || provider.isBlank() ? "fxcubic" : provider,
                fixVersion == null || fixVersion.isBlank() ? "FIX.4.3" : fixVersion,
                session,
                allowedSymbols == null ? Set.of() : Set.copyOf(allowedSymbols),
                maxOrderSize == null ? 0L : maxOrderSize,
                maxPositionPerSymbol == null ? 0D : maxPositionPerSymbol,
                maxTotalExposure == null ? 0D : maxTotalExposure,
                // Without a caller-supplied fingerprint every call would look
                // unchanged, so derive one from the connection fields.
                specFingerprint == null || specFingerprint.isBlank()
                        ? derivedFingerprint(session)
                        : specFingerprint,
                account);
    }

    private String derivedFingerprint(LpSessionSpec.FixSessionSpec session) {
        return Integer.toHexString(String.join("|",
                provider, fixVersion, session.host(), String.valueOf(session.port()),
                session.senderCompId(), session.targetCompId(), session.username(),
                session.password(), String.valueOf(session.useSsl()),
                String.valueOf(session.resetSeqNumOnLogon())).hashCode());
    }
}
