package com.yourorg.fixgateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Binds the "fix-gateway.providers.*" tree from application.yml.
 * Each provider can define a pricing session and/or a trading session -
 * FXCubic requires both, other providers may only need one.
 */
@Data
@ConfigurationProperties(prefix = "fix-gateway")
public class ProviderProperties {

    private Map<String, Provider> providers;
    private Pipeline pipeline = new Pipeline();
    private Persistence persistence = new Persistence();

    @Data
    public static class Provider {
        private String displayName;
        private String fixVersion;
        private Map<String, SessionConfig> sessions; // keys: "pricing", "trading"
        private Startup startup = new Startup();
        private Credentials credentials;
    }

    @Data
    public static class SessionConfig {
        private String senderCompId;
        private String targetCompId;
        private String host;
        private int port;
        private boolean resetSeqNumOnLogon;
        private boolean persistMessages;
        private int heartbeatIntervalSeconds = 30;
        private boolean useSsl;
        private String dataDictionary; // e.g. "FIX43.xml", resolved from classpath
    }

    @Data
    public static class Startup {
        private boolean subscribeMarketData;
        private List<String> symbols = List.of();
        private String marketDepth = "TOP_OF_BOOK";
    }

    @Data
    public static class Credentials {
        private String username;
        private String password;
    }

    @Data
    public static class Pipeline {
        private int inboundQueueCapacity = 10_000;
        private int outboundQueueCapacity = 5_000;
        private String overflowStrategy = "DROP_OLDEST";
    }

    @Data
    public static class Persistence {
        private String mode = "in-memory";
    }
}
