package com.aurify.fixclient.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the gateway manages the lifetime of caller-created sessions. These are
 * the only session knobs left in YAML - the connection details themselves come
 * from the caller on every request.
 */
@Data
@ConfigurationProperties(prefix = "fix-gateway.session")
public class SessionLifecycleProperties {

    /** Kept below the caller's RPC deadline so a slow logon fails as a clear error. */
    private int logonTimeoutSeconds = 8;

    /** Sessions unused for this long are logged out. Zero disables reaping. */
    private int idleTimeoutMinutes = 30;

    /** How often the reaper looks for idle sessions. */
    private long reaperIntervalMs = 60_000L;

    /** Where QuickFIX keeps sequence numbers. Must survive restarts. */
    private String fileStorePath = "data/store";

    /** Where QuickFIX writes raw FIX logs. Contains Logon messages - restrict access. */
    private String fileLogPath = "data/log";
}
