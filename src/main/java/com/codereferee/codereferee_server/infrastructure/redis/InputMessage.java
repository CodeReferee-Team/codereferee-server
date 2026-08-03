package com.codereferee.codereferee_server.infrastructure.redis;

import java.time.LocalDateTime;

public record InputMessage(
        String taskId,
        String repositoryUrl,
        String branch,
        String commitSha,
        LocalDateTime submittedAt
) {}
