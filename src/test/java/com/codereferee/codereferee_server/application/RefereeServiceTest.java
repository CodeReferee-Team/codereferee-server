package com.codereferee.codereferee_server.application;

import com.codereferee.codereferee_server.api.RepositoryValidationRequest;
import com.codereferee.codereferee_server.domain.validation.AgentStep;
import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import com.codereferee.codereferee_server.infrastructure.metrics.PipelineMetrics;
import com.codereferee.codereferee_server.infrastructure.persistence.TaskStatusPgRepository;
import com.codereferee.codereferee_server.infrastructure.redis.InputMessage;
import com.codereferee.codereferee_server.infrastructure.redis.RedisValidationRequestQueue;
import com.codereferee.codereferee_server.infrastructure.redis.TaskStatusRedisRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefereeServiceTest {

    // 테스트 대상 레포 — 여기만 바꾸면 아래 검증도 전부 따라온다
    private static final String REPO_URL = "https://github.com/phdcoco/QuickByte_Demo";
    private static final String BRANCH = "main";
    private static final String COMMIT = "d1c5c5e";

    private final TaskStatusRedisRepository taskStatusRedisRepository = mock(TaskStatusRedisRepository.class);
    private final TaskStatusPgRepository pgRepository = mock(TaskStatusPgRepository.class);
    private final RedisValidationRequestQueue redisValidationRequestQueue = mock(RedisValidationRequestQueue.class);
    private final PipelineMetrics pipelineMetrics = new PipelineMetrics(new SimpleMeterRegistry());
    private final RefereeService refereeService =
            new RefereeService(taskStatusRedisRepository, pgRepository, redisValidationRequestQueue, pipelineMetrics);

    @Test
    void submitStoresQueuedStatusAndEnqueuesTask() {
        RepositoryValidationRequest request = new RepositoryValidationRequest(REPO_URL, BRANCH, COMMIT);
        String requestId = refereeService.submit(request);

        ArgumentCaptor<TaskStatus> statusCaptor = ArgumentCaptor.forClass(TaskStatus.class);
        verify(taskStatusRedisRepository).save(statusCaptor.capture());
        TaskStatus status = statusCaptor.getValue();

        assertThat(status.taskId()).isEqualTo(requestId);
        assertThat(status.currentAgent()).isEqualTo(AgentStep.QUEUED);
        assertThat(status.executable()).isFalse();
        assertThat(status.iterationCount()).isZero();
        assertThat(status.repositoryUrl()).isEqualTo(REPO_URL);
        assertThat(status.branch()).isEqualTo(BRANCH);
        assertThat(status.commitSha()).isEqualTo(COMMIT);

        // 이력 조회를 위해 제출 시점부터 PG에도 기록된다.
        verify(pgRepository).upsert(status);

        ArgumentCaptor<InputMessage> messageCaptor = ArgumentCaptor.forClass(InputMessage.class);
        verify(redisValidationRequestQueue).enqueue(messageCaptor.capture());
        InputMessage message = messageCaptor.getValue();

        assertThat(message.taskId()).isEqualTo(requestId);
        assertThat(message.repositoryUrl()).isEqualTo(REPO_URL);
        assertThat(message.branch()).isEqualTo(BRANCH);
        assertThat(message.commitSha()).isEqualTo(COMMIT);
        assertThat(message.submittedAt()).isEqualTo(status.updatedAt());
    }

    @Test
    void submitAlwaysIssuesServerSideRequestId() {
        RepositoryValidationRequest request = new RepositoryValidationRequest(REPO_URL, BRANCH, COMMIT);

        String first = refereeService.submit(request);
        String second = refereeService.submit(request);

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void getStatusReadsStoredState() {
        TaskStatus status = new TaskStatus("task-1", AgentStep.QUEUED, false, 0, null, null);
        when(taskStatusRedisRepository.findById("task-1")).thenReturn(Optional.of(status));

        assertThat(refereeService.getStatus("task-1")).contains(status);
    }
}
