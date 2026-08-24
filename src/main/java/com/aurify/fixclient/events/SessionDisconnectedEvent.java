package com.aurify.fixclient.events;

import quickfix.SessionID;

public record SessionDisconnectedEvent(SessionID sessionId) {}
