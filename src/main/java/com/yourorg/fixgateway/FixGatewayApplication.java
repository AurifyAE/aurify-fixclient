package com.yourorg.fixgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FixGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(FixGatewayApplication.class, args);
    }
}
