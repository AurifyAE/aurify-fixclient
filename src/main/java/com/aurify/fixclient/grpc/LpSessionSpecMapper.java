package com.aurify.fixclient.grpc;

import aurify.lphedge.v1.FixSession;
import com.aurify.fixclient.session.LpSessionSpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Set;

/** Proto {@code LpSessionSpec} to the domain record. Kept out of the service so
 *  the generated types stop at the gRPC boundary, the same way raw QuickFIX
 *  messages stop at the transport boundary. */
public final class LpSessionSpecMapper {

    private LpSessionSpecMapper() {
    }

    public static LpSessionSpec toDomain(aurify.lphedge.v1.LpSessionSpec proto) {
        if (proto == null || proto.getLpAccountId().isBlank()) {
            throw new IllegalArgumentException("session is required: the gateway holds no LP configuration of its own");
        }
        if (!proto.hasTrading()) {
            throw new IllegalArgumentException("session.trading is required");
        }

        LpSessionSpec.FixSessionSpec trading = toDomain(proto.getTrading());
        Set<String> allowedSymbols = new LinkedHashSet<>(proto.getAllowedSymbolsList());

        String fingerprint = proto.getSpecFingerprint().isBlank()
                ? fingerprintOf(proto)   // caller did not supply one; derive it so
                : proto.getSpecFingerprint(); // credential edits still force a relogon

        return new LpSessionSpec(
                proto.getLpAccountId(),
                proto.getProvider(),
                proto.getFixVersion(),
                trading,
                allowedSymbols,
                proto.getMaxOrderSize(),
                proto.getMaxPositionPerSymbol(),
                proto.getMaxTotalExposure(),
                fingerprint,
                proto.getAccount(),
                proto.getPartyId());
    }

    private static LpSessionSpec.FixSessionSpec toDomain(FixSession proto) {
        return new LpSessionSpec.FixSessionSpec(
                proto.getHost(),
                proto.getPort(),
                proto.getSenderCompId(),
                proto.getTargetCompId(),
                proto.getUsername(),
                proto.getPassword(),
                proto.getUseSsl(),
                proto.getResetSeqNumOnLogon(),
                proto.getHeartbeatIntervalSeconds(),
                proto.getServerName(),
                proto.getStartTime(),
                proto.getEndTime());
    }

    /**
     * Digest of every field that would require a new logon if it changed.
     * Deliberately excludes risk limits and the symbol allowlist: those are
     * enforced per order and must not churn live sessions.
     */
    static String fingerprintOf(aurify.lphedge.v1.LpSessionSpec proto) {
        FixSession t = proto.getTrading();
        String material = String.join("|",
                proto.getProvider(),
                proto.getFixVersion(),
                t.getHost(),
                String.valueOf(t.getPort()),
                t.getSenderCompId(),
                t.getTargetCompId(),
                t.getUsername(),
                t.getPassword(),
                String.valueOf(t.getUseSsl()),
                String.valueOf(t.getResetSeqNumOnLogon()),
                String.valueOf(t.getHeartbeatIntervalSeconds()),
                t.getServerName(),
                t.getStartTime(),
                t.getEndTime());
        return sha256(material);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
