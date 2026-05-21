package com.codereferee.codereferee_server.referee;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public record TaskStatus(
        String taskId,
        AgentStep currentAgent,
        @JsonProperty("isExecutable") boolean executable,
        int iterationCount,
        String errorMessage,
        LocalDateTime updatedAt
) {
    public TaskStatus withStep(AgentStep step) {
        return new TaskStatus(taskId, step, executable, iterationCount, errorMessage, LocalDateTime.now());
    }

    public TaskStatus withSandboxSuccess() {
        return new TaskStatus(taskId, AgentStep.SANDBOX, true, iterationCount, null, LocalDateTime.now());
    }

    public TaskStatus withSandboxFailure(String error) {
        return new TaskStatus(taskId, AgentStep.SANDBOX, false, iterationCount, error, LocalDateTime.now());
    }

    public TaskStatus withNextIteration() {
        return new TaskStatus(taskId, currentAgent, executable, iterationCount + 1, errorMessage, LocalDateTime.now());
    }
}
