package com.codereferee.codereferee_server.referee;

import com.codereferee.codereferee_server.config.AiCoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefereeService {

    private final TaskStatusRepository taskStatusRepository;
    private final InputQueue inputQueue;
    private final PipelineMetrics pipelineMetrics;
    private final AiCoreClient aiCoreClient;
    private final AiCoreProperties aiCoreProperties;

    public String submit(RepositoryValidationRequest request) {
        String requestId = StringUtils.hasText(request.requestId())
                ? request.requestId()
                : UUID.randomUUID().toString();

        TaskStatus initial = new TaskStatus(
                requestId, AgentStep.DRAFT, false, 0, null, LocalDateTime.now(),
                request.repositoryUrl(), request.branch(), request.commitSha(), null
        );
        taskStatusRepository.save(initial);
        pipelineMetrics.recordSubmission();

        inputQueue.enqueue(new InputMessage(
                requestId, request.repositoryUrl(), request.branch(), request.commitSha(), initial.updatedAt()
        ));

        if (aiCoreProperties.enabled()) {
            initiateAiValidation(requestId, request, initial);
        } else {
            log.info("[AiCore] direct HTTP call disabled; requestId={} queued for Redis worker", requestId);
        }

        return requestId;
    }

    void initiateAiValidation(String requestId, RepositoryValidationRequest request, TaskStatus initial) {
        try {
            RepositoryValidationResponse response = aiCoreClient.validate(request);
            log.info("[AiCore] validation accepted requestId={} status={}",
                    requestId, response != null ? response.status() : "null");
        } catch (ResourceAccessException e) {
            log.error("[AiCore] timeout or connection failure requestId={}", requestId, e);
            taskStatusRepository.save(initial.withFailure("AI core timeout: " + e.getMessage()));
        } catch (HttpMessageConversionException e) {
            log.error("[AiCore] response schema mismatch requestId={}", requestId, e);
            taskStatusRepository.save(initial.withFailure("AI core parse error: " + e.getMessage()));
        }
    }

    public Optional<TaskStatus> getStatus(String requestId) {
        return taskStatusRepository.findById(requestId);
    }
}
