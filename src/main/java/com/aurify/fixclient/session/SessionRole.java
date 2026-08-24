package com.aurify.fixclient.session;

/** FXCubic-style LPs run two independent sessions per client. Not every
 *  provider will need both roles - treat as nullable/optional at call sites
 *  where a single-session provider is added later. */
public enum SessionRole { PRICING, TRADING }
