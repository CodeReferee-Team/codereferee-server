package com.codereferee.codereferee_server.application;

import com.codereferee.codereferee_server.domain.validation.AgentStep;
import com.codereferee.codereferee_server.domain.validation.ChaosOptions;
import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import com.codereferee.codereferee_server.domain.validation.TaskStatusHistoryRepository;
import com.codereferee.codereferee_server.domain.validation.TaskStatusRepository;
import com.codereferee.codereferee_server.infrastructure.metrics.PipelineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class StaleValidationSweeperTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 12, 0);

    private final TaskStatusRepository taskStatusRepository = mock(TaskStatusRepository.class);
    private final TaskStatusHistoryRepository historyRepository = mock(TaskStatusHistoryRepository.class);
    private final PipelineMetrics pipelineMetrics = new PipelineMetrics(new SimpleMeterRegistry());
    private final ValidationTimeoutProperties timeouts =
            new ValidationTimeoutProperties(Duration.ofMinutes(60), Duration.ofMinutes(30));
    private final StaleValidationSweeper sweeper =
            new StaleValidationSweeper(taskStatusRepository, historyRepository, pipelineMetrics, timeouts);

    private TaskStatus stuck(String taskId, AgentStep step) {
        return new TaskStatus(taskId, step, false, 0, null, NOW.minusHours(2),
                "https://github.com/phdcoco/QuickByte_Demo", "main", "d1c5c5e", ChaosOptions.NONE, null);
    }

    @Test
    void stuckRequestBecomesErrorNotFailed() {
        when(historyRepository.findStale(any(), any(), anyInt())).thenReturn(List.of(stuck("t1", AgentStep.BASELINE)));

        assertThat(sweeper.sweepAt(NOW)).isEqualTo(1);

        ArgumentCaptor<TaskStatus> captor = ArgumentCaptor.forClass(TaskStatus.class);
        verify(taskStatusRepository).save(captor.capture());
        TaskStatus saved = captor.getValue();

        // 사용자 코드를 판정한 결과가 아니라 판정 자체가 이뤄지지 않은 것이므로 ERROR다.
        assertThat(saved.currentAgent()).isEqualTo(AgentStep.ERROR);
        assertThat(saved.errorMessage()).contains("임계 시간을 초과");
        verify(historyRepository).upsert(saved);
    }

    @Test
    void queuedGetsALongerGraceThanRunningStages() {
        when(historyRepository.findStale(any(), any(), anyInt())).thenReturn(List.of());

        sweeper.sweepAt(NOW);

        ArgumentCaptor<LocalDateTime> queuedBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> runningBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(historyRepository).findStale(queuedBefore.capture(), runningBefore.capture(), anyInt());

        // 대기열은 샌드박스가 동시 1건만 처리해 길어질 수 있어 더 느슨해야 한다.
        assertThat(queuedBefore.getValue()).isEqualTo(NOW.minusMinutes(60));
        assertThat(runningBefore.getValue()).isEqualTo(NOW.minusMinutes(30));
        assertThat(queuedBefore.getValue()).isBefore(runningBefore.getValue());
    }

    @Test
    void nothingStaleMeansNoWrites() {
        when(historyRepository.findStale(any(), any(), anyInt())).thenReturn(List.of());

        assertThat(sweeper.sweepAt(NOW)).isZero();

        verify(taskStatusRepository, never()).save(any());
        verify(historyRepository, never()).upsert(any());
    }

    @Test
    void defaultsApplyWhenPropertiesAreAbsent() {
        ValidationTimeoutProperties defaults = new ValidationTimeoutProperties(null, null);
        assertThat(defaults.queuedTimeout()).isEqualTo(Duration.ofMinutes(60));
        assertThat(defaults.runningTimeout()).isEqualTo(Duration.ofMinutes(30));
    }
}
