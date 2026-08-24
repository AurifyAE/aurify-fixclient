package com.aurify.fixclient.events;

public record PipelineStateChangedEvent(String pipelineName, String state) {}
