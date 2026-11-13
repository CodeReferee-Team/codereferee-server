package com.codereferee.codereferee_server.application;

import com.codereferee.codereferee_server.domain.validation.*;
import com.codereferee.codereferee_server.infrastructure.metrics.PipelineMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefereeService {

    private final TaskStatusRepository taskStatusRepository;
    private final TaskStatusHistoryRepository historyRepository;
    private final ValidationRequestQueue requestQueue;
    private final PipelineMetrics pipelineMetrics;

    public String submit(String repositoryUrl, String branch, String commitSha,
                         String chaosMode, String deploymentProfile, Integer targetUsers,
                         String email) {
        // requestId는 항상 서버가 발급한다 (위조·중복 방지)
        String requestId = UUID.randomUUID().toString();

        TaskStatus initial = TaskStatus.queued(
                requestId, repositoryUrl, branch, commitSha, email,
                ChaosOptions.of(chaosMode, deploymentProfile, targetUsers), LocalDateTime.now()
        );
        taskStatusRepository.save(initial);
        historyRepository.upsert(initial);
        pipelineMetrics.recordSubmission();

        requestQueue.enqueue(initial);
        log.info("[Submit] requestId={} repo={} commit={} queued",
                requestId, repositoryUrl, commitSha);

        return requestId;
    }

    public Optional<TaskStatus> getStatus(String requestId) {
        return taskStatusRepository.findById(requestId);
    }

    /**
     * 대기 중인 요청 앞에 몇 건이 남았는지. 대기 중이 아니거나 알 수 없으면 null이다.
     *
     * 샌드박스는 experiment_lock으로 한 번에 한 건만 처리하고 AI 워커도 단일 루프라,
     * 파이프라인 전체가 직렬이다. 그래서 화면에 아무 변화가 없는 시간이 길어지는데,
     * 그때 사용자가 품는 의심은 "얼마나 남았나"가 아니라 "고장난 것 아닌가"다.
     * 순번만 보여줘도 그 의심이 사라진다.
     *
     * QUEUED일 때만 센다. 이미 돌기 시작했으면 순번은 의미가 없고, 폴링이 2초마다
     * 들어오므로 진행 중인 요청에까지 COUNT를 돌릴 이유가 없다.
     */
    public Integer queuedAhead(TaskStatus status) {
        if (status.currentAgent() != AgentStep.QUEUED || status.createdAt() == null) {
            return null;
        }
        return historyRepository.countAheadOf(status.taskId(), status.createdAt());
    }

    // 같은 repo + commit의 과거 검증 이력 검사, 재검사 시 이전 로그와 리포트 제공
    public List<TaskStatus> getHistory(String repositoryUrl, String commitSha) {
        return historyRepository.findByRepositoryAndCommit(repositoryUrl, commitSha);
    }
}
