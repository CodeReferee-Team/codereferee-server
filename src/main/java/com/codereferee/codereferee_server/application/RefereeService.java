package com.codereferee.codereferee_server.application;

import com.codereferee.codereferee_server.api.RepositoryValidationRequest;
import com.codereferee.codereferee_server.domain.validation.AgentStep;
import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import com.codereferee.codereferee_server.infrastructure.metrics.PipelineMetrics;
import com.codereferee.codereferee_server.infrastructure.persistence.TaskStatusPgRepository;
import com.codereferee.codereferee_server.infrastructure.redis.InputMessage;
import com.codereferee.codereferee_server.infrastructure.redis.RedisValidationRequestQueue;
import com.codereferee.codereferee_server.infrastructure.redis.TaskStatusRedisRepository;
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

    private final TaskStatusRedisRepository taskStatusRedisRepository;
    private final TaskStatusPgRepository pgRepository;
    private final RedisValidationRequestQueue redisValidationRequestQueue;
    private final PipelineMetrics pipelineMetrics;

    public String submit(RepositoryValidationRequest request) {
        // requestId는 항상 서버가 발급한다 (위조·중복 방지)
        String requestId = UUID.randomUUID().toString();

        TaskStatus initial = new TaskStatus(
                requestId, AgentStep.QUEUED, false, 0, null, LocalDateTime.now(),
                request.repositoryUrl(), request.branch(), request.commitSha(), null
        );
        taskStatusRedisRepository.save(initial);
        pgRepository.upsert(initial);
        pipelineMetrics.recordSubmission();

        redisValidationRequestQueue.enqueue(new InputMessage(
                requestId, request.repositoryUrl(), request.branch(), request.commitSha(), initial.updatedAt()
        ));
        log.info("[Submit] requestId={} repo={} commit={} queued", requestId,
                request.repositoryUrl(), request.commitSha());

        return requestId;
    }

    public Optional<TaskStatus> getStatus(String requestId) {
        return taskStatusRedisRepository.findById(requestId);
    }

    /** 같은 repo+commit의 과거 검증 이력 — 재검사 시 이전 로그·리포트 제공용 */
    public List<TaskStatus> getHistory(String repositoryUrl, String commitSha) {
        return pgRepository.findByRepositoryAndCommit(repositoryUrl, commitSha);
    }
}
