package com.codereferee.codereferee_server.application;

import com.codereferee.codereferee_server.api.RepositoryValidationRequest;
import com.codereferee.codereferee_server.domain.validation.*;
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

    // 테스트 대상 레포
    private static final String REPO_URL = "https://github.com/phdcoco/QuickByte_Demo";
    private static final String BRANCH = "main";
    private static final String COMMIT = "d1c5c5e";

    private final TaskStatusRepository taskStatusRepository = mock(TaskStatusRepository.class);
    private final TaskStatusHistoryRepository historyRepository = mock(TaskStatusHistoryRepository.class);
    private final ValidationRequestQueue requestQueue = mock(ValidationRequestQueue.class);
    private final PipelineMetrics pipelineMetrics = new PipelineMetrics(new SimpleMeterRegistry());
    private final RefereeService refereeService =
            new RefereeService(taskStatusRepository, historyRepository, requestQueue, pipelineMetrics);

    @Test
    void submitStoresQueuedStatusAndEnqueuesTask() {
        String requestId = refereeService.submit(REPO_URL, BRANCH, COMMIT);

        ArgumentCaptor<TaskStatus> statusCaptor = ArgumentCaptor.forClass(TaskStatus.class);
        verify(taskStatusRepository).save(statusCaptor.capture());
        TaskStatus status = statusCaptor.getValue();

        assertThat(status.taskId()).isEqualTo(requestId);
        assertThat(status.currentAgent()).isEqualTo(AgentStep.QUEUED);
        assertThat(status.executable()).isFalse();
        assertThat(status.iterationCount()).isZero();
        assertThat(status.repositoryUrl()).isEqualTo(REPO_URL);
        assertThat(status.branch()).isEqualTo(BRANCH);
        assertThat(status.commitSha()).isEqualTo(COMMIT);

        // 이력 조회를 위해 제출 시점부터 PG에도 기록된다.
        verify(historyRepository).upsert(status);

        // 큐에는 저장된 것과 동일한 초기 상태가 전달된다.
        verify(requestQueue).enqueue(status);
    }

    @Test
    void submitAlwaysIssuesServerSideRequestId() {

        String first = refereeService.submit(REPO_URL, BRANCH, COMMIT);
        String second = refereeService.submit(REPO_URL, BRANCH, COMMIT);

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void getStatusReadsStoredState() {
        TaskStatus status = new TaskStatus("task-1", AgentStep.QUEUED, false, 0, null, null);
        when(taskStatusRepository.findById("task-1")).thenReturn(Optional.of(status));

        assertThat(refereeService.getStatus("task-1")).contains(status);
    }
}
