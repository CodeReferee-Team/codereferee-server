package com.codereferee.codereferee_server.referee;

import java.time.LocalDateTime;

public record DraftTaskMessage(
        String taskId,
        String requirements,
        LocalDateTime submittedAt
) {
}
