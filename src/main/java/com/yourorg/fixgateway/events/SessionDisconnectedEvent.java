package com.yourorg.fixgateway.events;

import quickfix.SessionID;

public record SessionDisconnectedEvent(SessionID sessionId) {}
