package com.codereferee.codereferee_server.referee;

import java.time.LocalDateTime;

public record DraftTaskMessage(
        String taskId,
        String repositoryUrl,
        String branch,
        String commitSha,
        LocalDateTime submittedAt
) {}
