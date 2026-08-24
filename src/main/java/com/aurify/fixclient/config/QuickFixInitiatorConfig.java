package com.aurify.fixclient.config;

import com.aurify.fixclient.transport.GatewayFixApplication;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import quickfix.*;

/** Wires the generated SessionSettings into an actual QuickFIX/J
 *  SocketInitiator and starts it with the application lifecycle. */
@Slf4j
@Configuration
@EnableConfigurationProperties(ProviderProperties.class)
@RequiredArgsConstructor
public class QuickFixInitiatorConfig {

    private final QuickFixSessionConfigFactory sessionConfigFactory;
    private final GatewayFixApplication gatewayFixApplication;

    @Bean
    public SessionSettings sessionSettings() {
        return sessionConfigFactory.buildSessionSettings();
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SocketInitiator socketInitiator(SessionSettings sessionSettings) throws ConfigError {
        MessageStoreFactory storeFactory = new FileStoreFactory(sessionSettings);
        LogFactory logFactory = new FileLogFactory(sessionSettings);
        MessageFactory messageFactory = new DefaultMessageFactory();

        SocketInitiator initiator = new SocketInitiator(
                gatewayFixApplication, storeFactory, sessionSettings, logFactory, messageFactory);

        log.info("Starting QuickFIX/J SocketInitiator with {} session(s)", sessionSettings.size());
        return initiator;
    }
}
