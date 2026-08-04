package com.codereferee.codereferee_server.domain.validation;

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

    private TaskStatus rebuild(AgentStep step, boolean exec, int iterations, String error) {
        return new TaskStatus(taskId, step, exec, iterations, error, LocalDateTime.now(),
                repositoryUrl, branch, commitSha, aiReports);
    }

    public TaskStatus withStep(AgentStep step) {
        return rebuild(step, executable, iterationCount, errorMessage);
    }

    // 중간 진행 이벤트 반영 -> 단계를 전환하고 Refine 라운드를 갱신한다.
    public TaskStatus withProgress(AgentStep step, int iterations) {
        return rebuild(step, executable, Math.max(iterations, iterationCount), errorMessage);
    }

    public TaskStatus withNextIteration() {
        return rebuild(currentAgent, executable, iterationCount + 1, errorMessage);
    }

    // 코드 결함으로 인한 최종 실패
    public TaskStatus withFailure(String error) {
        return rebuild(AgentStep.FAILED, false, iterationCount, error);
    }

    // 인프라, 파이프라인 오류로 인해 판정 불가, Critic 루프 제외
    public TaskStatus withError(String error) {
        return rebuild(AgentStep.ERROR, executable, iterationCount, error);
    }

    public TaskStatus withAiResult(AgentStep step, boolean exec, String error, Map<String, Object> reports) {
        return new TaskStatus(taskId, step, exec, iterationCount, error, LocalDateTime.now(),
                repositoryUrl, branch, commitSha, reports);
    }
}
