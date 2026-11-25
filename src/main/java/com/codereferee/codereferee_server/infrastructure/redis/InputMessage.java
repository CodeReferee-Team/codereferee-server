package com.codereferee.codereferee_server.infrastructure.redis;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonInclude;

public record InputMessage(
        String taskId,
        String repositoryUrl,
        String branch,
        String commitSha,
        String chaosMode,
        String deploymentProfile,
        Integer targetUsers,
        LocalDateTime submittedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String validationYaml
) {}
