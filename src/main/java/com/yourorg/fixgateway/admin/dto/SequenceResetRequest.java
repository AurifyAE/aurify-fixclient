package com.yourorg.fixgateway.admin.dto;

public record SequenceResetRequest(int nextInboundSeqNum, int nextOutboundSeqNum) {}
