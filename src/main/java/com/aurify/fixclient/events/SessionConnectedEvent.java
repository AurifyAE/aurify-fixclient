package com.aurify.fixclient.events;

import quickfix.SessionID;

public record SessionConnectedEvent(SessionID sessionId) {}
