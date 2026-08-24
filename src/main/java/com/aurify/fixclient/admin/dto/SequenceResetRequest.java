package com.aurify.fixclient.admin.dto;

public record SequenceResetRequest(int nextInboundSeqNum, int nextOutboundSeqNum) {}
