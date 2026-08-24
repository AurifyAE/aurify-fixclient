package com.yourorg.fixgateway.config;

import com.yourorg.fixgateway.session.SessionRole;
import lombok.Value;

/** Ties a raw QuickFIX SessionID back to "which provider, which role" - and
 *  carries the login credentials needed to answer Logon (toAdmin). */
@Value
public class SessionMetadata {
    String providerName;
    SessionRole role;
    String username;
    String password;
}
