package com.codereferee.codereferee_server.referee;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.Map;

public record TaskStatus(
        String taskId,
        AgentStep currentAgent,
        @JsonProperty("isExecutable") boolean executable,
        int iterationCount,
        String errorMessage,
        LocalDateTime updatedAt,
        String repositoryUrl,
        String branch,
        String commitSha,
        Map<String, Object> aiReports
) {
    public TaskStatus(String taskId, AgentStep currentAgent, boolean executable,
                      int iterationCount, String errorMessage, LocalDateTime updatedAt) {
        this(taskId, currentAgent, executable, iterationCount, errorMessage, updatedAt, null, null, null, null);
    }

    // Single constructor call site — all with* methods delegate here
    private TaskStatus rebuild(AgentStep step, boolean exec, int iterations, String error) {
        return new TaskStatus(taskId, step, exec, iterations, error, LocalDateTime.now(),
                repositoryUrl, branch, commitSha, aiReports);
    }

    public TaskStatus withStep(AgentStep step) {
        return rebuild(step, executable, iterationCount, errorMessage);
    }

    public TaskStatus withSandboxSuccess() {
        return rebuild(AgentStep.SANDBOX, true, iterationCount, null);
    }

    public TaskStatus withSandboxFailure(String error) {
        return rebuild(AgentStep.SANDBOX, false, iterationCount, error);
    }

    public TaskStatus withNextIteration() {
        return rebuild(currentAgent, executable, iterationCount + 1, errorMessage);
    }

    public TaskStatus withFailure(String error) {
        return rebuild(AgentStep.FAILED, false, iterationCount, error);
    }
}
