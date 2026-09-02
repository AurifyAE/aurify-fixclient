package com.aurify.fixclient.config;

import com.aurify.fixclient.transport.GatewayFixApplication;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import quickfix.*;

/** Starts an initiator with no sessions. Sessions are added at runtime by
 *  DynamicSessionManager from specs the caller supplies, so this SessionSettings
 *  bean is shared, mutable state - createDynamicSession reads back out of it. */
@Slf4j
@Configuration
@EnableScheduling
@EnableConfigurationProperties({ProviderProperties.class, SessionLifecycleProperties.class})
@RequiredArgsConstructor
public class QuickFixInitiatorConfig {

    private final QuickFixSessionConfigFactory sessionConfigFactory;
    private final GatewayFixApplication gatewayFixApplication;

    @Bean
    public SessionSettings sessionSettings() {
        return sessionConfigFactory.baseSettings();
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SocketInitiator socketInitiator(SessionSettings sessionSettings) throws ConfigError {
        MessageStoreFactory storeFactory = new FileStoreFactory(sessionSettings);
        LogFactory logFactory = new FileLogFactory(sessionSettings);
        MessageFactory messageFactory = new DefaultMessageFactory();

        SocketInitiator initiator = new SocketInitiator(
                gatewayFixApplication, storeFactory, sessionSettings, logFactory, messageFactory);

        log.info("Starting QuickFIX/J SocketInitiator with no static sessions - "
                + "sessions are created on demand from caller-supplied specs");
        return initiator;
    }
}
