package com.aurify.fixclient.pipeline;

import com.aurify.fixclient.canonical.event.CanonicalEvent;
import quickfix.SessionID;

/**
 * A canonical event together with the transport facts the canonical DTOs
 * deliberately do not carry: which session it arrived on, and the FIX message
 * verbatim.
 *
 * Keeping these beside the event rather than inside it preserves the rule that
 * canonical types are provider- and transport-neutral, while still letting the
 * execution journal record an auditable trail and attribute a report to an LP
 * account.
 */
public record InboundEvent(SessionID sessionId, String rawFix, CanonicalEvent event) {}
