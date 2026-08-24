package com.yourorg.fixgateway.config;

import com.yourorg.fixgateway.session.SessionRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import quickfix.SessionID;
import quickfix.SessionSettings;

import java.util.Map;

/**
 * Turns the "fix-gateway.providers.*" YAML tree into a QuickFIX/J
 * SessionSettings object, and records provider/role/credentials for each
 * generated SessionID into a SessionMetadataRegistry.
 *
 * This is the piece that lets you add a brand new provider by editing YAML
 * only - no Java changes needed for a standard session.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuickFixSessionConfigFactory {

    private final ProviderProperties providerProperties;
    private final SessionMetadataRegistry sessionMetadataRegistry;

    public SessionSettings buildSessionSettings() {
        SessionSettings settings = new SessionSettings();

        // Defaults shared by every session unless overridden
        settings.setString("ConnectionType", "initiator");
        settings.setString("FileStorePath", "data/store");
        settings.setString("FileLogPath", "data/log");
        settings.setBool("UseDataDictionary", true);

        for (Map.Entry<String, ProviderProperties.Provider> providerEntry : providerProperties.getProviders().entrySet()) {
            String providerName = providerEntry.getKey();
            ProviderProperties.Provider provider = providerEntry.getValue();

            if (provider.getSessions() == null) {
                log.warn("Provider '{}' has no sessions configured, skipping", providerName);
                continue;
            }

            for (Map.Entry<String, ProviderProperties.SessionConfig> sessionEntry : provider.getSessions().entrySet()) {
                SessionRole role = SessionRole.valueOf(sessionEntry.getKey().toUpperCase());
                ProviderProperties.SessionConfig cfg = sessionEntry.getValue();

                SessionID sessionId = new SessionID(
                        provider.getFixVersion(),
                        cfg.getSenderCompId(),
                        cfg.getTargetCompId(),
                        role.name() // SessionQualifier - keeps pricing/trading distinct even
                                    // if a provider reuses comp IDs across both sessions
                );

                applySessionSettings(settings, sessionId, cfg);

                sessionMetadataRegistry.put(sessionId, new SessionMetadata(
                        providerName,
                        role,
                        provider.getCredentials() != null ? provider.getCredentials().getUsername() : null,
                        provider.getCredentials() != null ? provider.getCredentials().getPassword() : null
                ));

                log.info("Configured {} [{}] session -> {}:{}", providerName, role, cfg.getHost(), cfg.getPort());
            }
        }

        return settings;
    }

    private void applySessionSettings(SessionSettings settings, SessionID sessionId,
                                       ProviderProperties.SessionConfig cfg) {
        settings.setString(sessionId, "SocketConnectHost", cfg.getHost());
        settings.setLong(sessionId, "SocketConnectPort", cfg.getPort());
        settings.setLong(sessionId, "HeartBtInt", cfg.getHeartbeatIntervalSeconds());
        settings.setBool(sessionId, "ResetOnLogon", cfg.isResetSeqNumOnLogon());
        settings.setBool(sessionId, "PersistMessages", cfg.isPersistMessages());
        settings.setString(sessionId, "StartTime", "00:00:00");
        settings.setString(sessionId, "EndTime", "23:59:59");

        if (cfg.getDataDictionary() != null) {
            settings.setString(sessionId, "DataDictionary", cfg.getDataDictionary());
        }

        if (cfg.isUseSsl()) {
            settings.setString(sessionId, "SocketUseSSL", "Y");
        }
    }
}
