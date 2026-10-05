package com.codereferee.codereferee_server.domain.validation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

public record TaskStatus(
        String taskId,
        AgentStep currentAgent,
        @JsonProperty("isExecutable") boolean executable,
        int iterationCount,
        String errorMessage,
        /**
         * 요청이 접수된 시각. 전이마다 덮어쓰이는 updatedAt과 달리 한 번 정해지면 바뀌지 않는다.
         * 이 값이 없으면 요청 전체 소요시간을 알 수 없다.
         *
         * 칼럼 추가 이전에 저장된 레코드에는 참값이 없어 null로 읽힌다. 그런 행을 updatedAt으로
         * 메우면 소요시간이 0에 가깝게 집계되어 조용히 틀리므로, null을 그대로 두고
         * {@link #elapsed()}가 비어 있는 값을 돌려주게 해서 집계에서 빠지도록 한다.
         */
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String repositoryUrl,
        String branch,
        String commitSha,
        ChaosOptions chaosOptions,
        Map<String, Object> aiReports
) {
    public TaskStatus {
        // Redis에 저장된 옛 레코드에는 이 필드가 없어 null로 역직렬화된다.
        chaosOptions = chaosOptions != null ? chaosOptions : ChaosOptions.NONE;
    }

    /** 지금 만들어지는 상태. 생성 시각과 갱신 시각이 같다. */
    public TaskStatus(String taskId, AgentStep currentAgent, boolean executable,
                      int iterationCount, String errorMessage, LocalDateTime updatedAt) {
        this(taskId, currentAgent, executable, iterationCount, errorMessage, updatedAt, updatedAt,
                null, null, null, ChaosOptions.NONE, null);
    }

    /** 새로 접수된 검증 요청. */
    public static TaskStatus queued(String taskId, String repositoryUrl, String branch,
                                    String commitSha, ChaosOptions chaosOptions, LocalDateTime now) {
        return new TaskStatus(taskId, AgentStep.QUEUED, false, 0, null, now, now,
                repositoryUrl, branch, commitSha, chaosOptions, null);
    }

    /** 접수부터 마지막 갱신까지 걸린 시간. 생성 시각을 모르는 옛 레코드는 비어 있다. */
    public Optional<Duration> elapsed() {
        if (createdAt == null || updatedAt == null) return Optional.empty();
        return Optional.of(Duration.between(createdAt, updatedAt));
    }

    /** 마지막 갱신부터 {@code next}까지, 즉 현재 단계에 머문 시간. */
    public Optional<Duration> dwellUntil(LocalDateTime next) {
        if (updatedAt == null || next == null) return Optional.empty();
        return Optional.of(Duration.between(updatedAt, next));
    }

    private TaskStatus rebuild(AgentStep step, boolean exec, int iterations, String error) {
        return new TaskStatus(taskId, step, exec, iterations, error, createdAt, LocalDateTime.now(),
                repositoryUrl, branch, commitSha, chaosOptions, aiReports);
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
        return new TaskStatus(taskId, step, exec, iterationCount, error, createdAt, LocalDateTime.now(),
                repositoryUrl, branch, commitSha, chaosOptions, reports);
    }
}
