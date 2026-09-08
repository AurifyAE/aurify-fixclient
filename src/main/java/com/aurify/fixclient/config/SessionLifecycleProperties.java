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

    /**
     * Kept below the caller's RPC deadline so a slow logon fails as a clear error.
     *
     * Also drives QuickFIX's own LogonTimeout (see QuickFixSessionConfigFactory):
     * leaving that at its 10s default caps every logon regardless of this value.
     */
    private int logonTimeoutSeconds = 25;

    /**
     * How many times to offer the Logon before giving up, sharing the budget
     * above rather than adding to it - two attempts of 12s, not two of 25s -
     * so the caller's deadline arithmetic is unchanged.
     *
     * A venue can drop a connection with no FIX-level answer at all: Finalto's
     * demo does it to roughly every other connect for a comp ID pair that
     * recently logged out, and the Logon we send is byte-identical on the
     * attempt that fails and the one that succeeds. A logon that is going to
     * succeed does so in about a second, so a failed attempt is dead time, not
     * a slow one. Set to 1 to restore single-shot behaviour.
     */
    private int logonAttempts = 2;

    /** Sessions unused for this long are logged out. Zero disables reaping. */
    private int idleTimeoutMinutes = 30;

    /** How often the reaper looks for idle sessions. */
    private long reaperIntervalMs = 60_000L;

    /** Where QuickFIX keeps sequence numbers. Must survive restarts. */
    private String fileStorePath = "data/store";

    /** Where QuickFIX writes raw FIX logs. Contains Logon messages - restrict access. */
    private String fileLogPath = "data/log";
}
