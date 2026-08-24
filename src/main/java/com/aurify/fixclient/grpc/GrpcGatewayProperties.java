package com.aurify.fixclient.grpc;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "fix-gateway.grpc")
public class GrpcGatewayProperties {
    private int port = 9090;
    private String provider = "fxcubic";
    private long orderTimeoutMs = 10_000;
}
