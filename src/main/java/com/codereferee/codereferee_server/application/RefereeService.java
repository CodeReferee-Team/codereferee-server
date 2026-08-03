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

    public String submit(String repositoryUrl, String branch, String commitSha) {
        // requestId는 항상 서버가 발급한다 (위조·중복 방지)
        String requestId = UUID.randomUUID().toString();

        TaskStatus initial = new TaskStatus(
                requestId, AgentStep.QUEUED, false, 0, null, LocalDateTime.now(),
                repositoryUrl, branch, commitSha, null
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

    // 같은 repo + commit의 과거 검증 이력 검사, 재검사 시 이전 로그와 리포트 제공
    public List<TaskStatus> getHistory(String repositoryUrl, String commitSha) {
        return historyRepository.findByRepositoryAndCommit(repositoryUrl, commitSha);
    }
}
