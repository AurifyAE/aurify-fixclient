package com.aurify.fixclient.config;

import com.aurify.fixclient.provider.ProviderAdapterRegistry;
import com.aurify.fixclient.session.LpSessionSpec;
import com.aurify.fixclient.session.SessionRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.mina.ssl.SSLSupport;

/**
 * Turns a caller-supplied {@link LpSessionSpec} into QuickFIX/J settings.
 *
 * The gateway no longer owns any LP configuration: it starts with an empty
 * SessionSettings and each session's entry is written in just before that
 * session is created dynamically.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuickFixSessionConfigFactory {

    private final SessionLifecycleProperties lifecycleProperties;
    private final ProviderAdapterRegistry adapterRegistry;

    /** Settings the initiator starts with: shared defaults, zero sessions. */
    public SessionSettings baseSettings() {
        SessionSettings settings = new SessionSettings();
        settings.setString("ConnectionType", "initiator");
        settings.setString("FileStorePath", lifecycleProperties.getFileStorePath());
        settings.setString("FileLogPath", lifecycleProperties.getFileLogPath());
        settings.setBool("UseDataDictionary", true);
        return settings;
    }

    /**
     * SessionID for an LP account. The qualifier is derived from lpAccountId
     * and role only - never from the spec fingerprint - because the file store
     * path is keyed on SessionID, and a changing qualifier would orphan the
     * sequence numbers on every credential edit.
     */
    public SessionID sessionIdFor(LpSessionSpec spec, SessionRole role) {
        LpSessionSpec.FixSessionSpec session = sessionOf(spec, role);
        return new SessionID(
                spec.fixVersion(),
                session.senderCompId(),
                session.targetCompId(),
                spec.lpAccountId() + "-" + role.name());
    }

    /** Writes one session's connection settings into the initiator's settings object. */
    public void applySpec(SessionSettings settings, SessionID sessionId,
                          LpSessionSpec spec, SessionRole role) {
        LpSessionSpec.FixSessionSpec session = sessionOf(spec, role);

        settings.setString(sessionId, "SocketConnectHost", session.host());
        settings.setLong(sessionId, "SocketConnectPort", session.port());
        settings.setLong(sessionId, "HeartBtInt", session.heartbeatIntervalSeconds());
        settings.setBool(sessionId, "ResetOnLogon", session.resetSeqNumOnLogon());
        settings.setBool(sessionId, "PersistMessages", true);
        settings.setString(sessionId, "StartTime", session.startTime());
        settings.setString(sessionId, "EndTime", session.endTime());
        settings.setString(sessionId, "DataDictionary", dataDictionaryFor(spec));
        settings.setBool(sessionId, Session.SETTING_VALIDATE_USER_DEFINED_FIELDS,
                validateUserDefinedFieldsFor(spec));

        // QuickFIX runs its OWN logon timer (LogonTimeout, default 10s) and
        // disconnects with "Timed out waiting for logon response" when it
        // expires. Left unset, that 10s silently caps how long an LP is given
        // to answer no matter what DynamicSessionManager.awaitLogon waits for,
        // so a slow venue can never log on and the cause is invisible from the
        // gateway's own configuration. Kept just inside our wait so that when
        // an LP really is too slow, awaitLogon is what reports it.
        settings.setLong(sessionId, Session.SETTING_LOGON_TIMEOUT,
                Math.max(1, lifecycleProperties.getLogonTimeoutSeconds() - 1));

        if (session.useSsl()) {
            settings.setString(sessionId, SSLSupport.SETTING_USE_SSL, "Y");
            if (session.serverName() != null && !session.serverName().isBlank()) {
                settings.setString(sessionId, SSLSupport.SETTING_USE_SNI, "Y");
            }
        }
    }

    /** The session of a given role, or a clear error if the caller omitted it. */
    public static LpSessionSpec.FixSessionSpec sessionOf(LpSessionSpec spec, SessionRole role) {
        if (role == SessionRole.TRADING) {
            return spec.trading();
        }
        throw new IllegalArgumentException(
                "No " + role + " session configured for LP account " + spec.lpAccountId());
    }

    /**
     * The adapter picks the dictionary, because which enums a session must
     * tolerate is a property of the liquidity provider, not of the FIX version.
     * Falls back to the stock dictionary ("FIX.4.3" -> "FIX43.xml") when no
     * adapter is registered for the provider.
     */
    String dataDictionaryFor(LpSessionSpec spec) {
        return adapterRegistry.resolve(spec.provider())
                .map(adapter -> adapter.dataDictionary(spec.fixVersion()))
                .orElseGet(() -> spec.fixVersion().replace(".", "") + ".xml");
    }

    /** Defaults to QuickFIX's own default (true) when no adapter is registered. */
    boolean validateUserDefinedFieldsFor(LpSessionSpec spec) {
        return adapterRegistry.resolve(spec.provider())
                .map(com.aurify.fixclient.provider.LiquidityProviderAdapter::validateUserDefinedFields)
                .orElse(true);
    }
}
