package com.codereferee.codereferee_server.referee;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefereeService {

    private final TaskStatusRepository taskStatusRepository;
    private final PipelineRouter pipelineRouter;
    private final PipelineMetrics pipelineMetrics;

    public String submit(String requirements) {
        String taskId = UUID.randomUUID().toString();

        TaskStatus initial = new TaskStatus(
                taskId,
                AgentStep.DRAFT,
                false,
                0,
                null,
                LocalDateTime.now()
        );
        taskStatusRepository.save(initial);
        pipelineMetrics.recordSubmission();

        triggerPipeline(taskId, requirements);

        return taskId;
    }

    public Optional<TaskStatus> getStatus(String taskId) {
        return taskStatusRepository.findById(taskId);
    }

    // TODO: 비동기 에이전트 파이프라인 트리거 (현재 Mock)
    private void triggerPipeline(String taskId, String requirements) {
    }
}
