package com.aurify.fixclient.session;

/** Lifecycle of one LP FIX session as this gateway sees it. */
public enum SessionState {
    /** Never created, or torn down after an idle timeout. */
    ABSENT,
    /** Dynamic session created; TCP/SSL/Logon in progress. */
    CONNECTING,
    /** Logon acknowledged by the LP; orders may be sent. */
    LOGGED_ON,
    /** Logon timed out or was rejected. Retried on the next ensureSession. */
    FAILED
}
