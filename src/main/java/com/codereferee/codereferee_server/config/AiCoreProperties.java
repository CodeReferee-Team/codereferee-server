package com.codereferee.codereferee_server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.core")
public record AiCoreProperties(
        boolean enabled,
        String baseUrl,
        String repositoryValidationPath
) {}
