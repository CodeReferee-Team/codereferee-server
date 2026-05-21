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
    private final DraftTaskQueue draftTaskQueue;
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

        enqueueDraftTask(taskId, requirements, initial.updatedAt());

        return taskId;
    }

    public Optional<TaskStatus> getStatus(String taskId) {
        return taskStatusRepository.findById(taskId);
    }

    private void enqueueDraftTask(String taskId, String requirements, LocalDateTime submittedAt) {
        draftTaskQueue.enqueue(new DraftTaskMessage(taskId, requirements, submittedAt));
    }
}
