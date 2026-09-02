package com.aurify.fixclient.session;

/** Session could not be established or is unusable. Carries a stable code so
 *  the gRPC layer can map it to something the caller can act on. */
public class LpSessionException extends RuntimeException {

    private final String code;

    public LpSessionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public LpSessionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static LpSessionException logonTimeout(String lpAccountId, long seconds) {
        return new LpSessionException("LP_SESSION_LOGON_TIMEOUT",
                "Logon to LP account " + lpAccountId + " did not complete within " + seconds + "s");
    }

    public static LpSessionException createFailed(String lpAccountId, Throwable cause) {
        return new LpSessionException("LP_SESSION_CREATE_FAILED",
                "Could not create FIX session for LP account " + lpAccountId + ": " + cause.getMessage(), cause);
    }
}
