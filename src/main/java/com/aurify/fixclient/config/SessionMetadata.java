package com.aurify.fixclient.config;

import com.aurify.fixclient.session.SessionRole;
import lombok.Value;

/** Ties a raw QuickFIX SessionID back to "which LP account, which provider,
 *  which role" - and carries the login credentials needed to answer Logon
 *  (toAdmin). Written by DynamicSessionManager just before the session is
 *  created, so it is always present by the time onCreate fires.
 *
 *  Holds a FIX password: toString() is overridden to keep it out of logs. */
@Value
public class SessionMetadata {
    String providerName;
    SessionRole role;
    String username;
    String password;
    String lpAccountId;

    @Override
    public String toString() {
        return "SessionMetadata[lpAccountId=" + lpAccountId
                + ", provider=" + providerName
                + ", role=" + role
                + ", username=" + username
                + ", password=" + (password == null || password.isEmpty() ? "<none>" : "***") + "]";
    }
}
