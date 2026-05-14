package com.codereferee.codereferee_server.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sandbox")
public record SandboxProperties(String baseUrl) {
}
