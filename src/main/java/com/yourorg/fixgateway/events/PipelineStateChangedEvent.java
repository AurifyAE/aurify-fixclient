package com.yourorg.fixgateway.events;

public record PipelineStateChangedEvent(String pipelineName, String state) {}
