package com.aurify.fixclient.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway-wide settings from application.yml.
 *
 * Note what is NOT here any more: providers, hosts, comp IDs and credentials.
 * Those belong to the caller and arrive on every request as an LpSessionSpec,
 * which is what makes this gateway deployable against any LP without a rebuild.
 */
@Data
@ConfigurationProperties(prefix = "fix-gateway")
public class ProviderProperties {

    private Pipeline pipeline = new Pipeline();
    private Persistence persistence = new Persistence();

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
