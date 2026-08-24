package com.yourorg.fixgateway.events;

import quickfix.SessionID;

public record SessionConnectedEvent(SessionID sessionId) {}
