package com.codereferee.codereferee_server.referee;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SandboxResultMessage(
        String taskId,
        @JsonProperty("isExecutable") boolean executable,
        String errorMessage
) {}
