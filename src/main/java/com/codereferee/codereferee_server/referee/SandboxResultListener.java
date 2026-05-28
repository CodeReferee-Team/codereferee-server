package com.codereferee.codereferee_server.referee;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxResultListener implements MessageListener {

    private final TaskStatusRepository taskStatusRepository;
    private final TaskStatusPgRepository pgRepository;
    private final PipelineMetrics pipelineMetrics;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            SandboxResultMessage msg = objectMapper.readValue(message.getBody(), SandboxResultMessage.class);

            String taskId = msg.requestId() != null ? msg.requestId() : msg.jobId();
            log.info("[AI Output] received taskId={} status={}", taskId, msg.status());

            TaskStatus current = taskStatusRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("TaskStatus not found: " + taskId));

            boolean success = "success".equals(msg.status());
            AgentStep finalStep = success ? AgentStep.COMPLETED : AgentStep.FAILED;
            String errorMessage = success ? null : "AI pipeline ended with status: " + msg.status();

            Map<String, Object> aiReports = buildAiReports(msg);

            TaskStatus updated = current.withAiResult(finalStep, success, errorMessage, aiReports);

            pipelineMetrics.recordTransition(current.currentAgent(), finalStep);
            if (!success) pipelineMetrics.recordSandboxFailure();

            taskStatusRepository.save(updated);
            pgRepository.upsert(updated);

            log.info("[AI Output] taskId={} → {} reports={}", taskId, finalStep, aiReports.keySet());
        } catch (Exception e) {
            log.error("[AI Output] message processing failed", e);
        }
    }

    private Map<String, Object> buildAiReports(SandboxResultMessage msg) {
        Map<String, Object> reports = new LinkedHashMap<>();
        if (msg.preflightReport() != null)                           reports.put("preflight_report", msg.preflightReport());
        if (msg.executionResult() != null)                           reports.put("execution_result", msg.executionResult());
        putIfNotEmpty(reports, "judge_report",    msg.judgeReport());
        putIfNotEmpty(reports, "critic_feedback", msg.criticFeedback());
        putIfNotEmpty(reports, "refiner_report",  msg.refinerReport());
        putIfNotEmpty(reports, "validation_plan", msg.validationPlan());
        putIfNotEmpty(reports, "metrics",         msg.metrics());
        if (msg.events() != null && !msg.events().isEmpty())         reports.put("events", msg.events());
        return reports;
    }

    private void putIfNotEmpty(Map<String, Object> target, String key, Map<String, Object> value) {
        if (value != null && !value.isEmpty()) target.put(key, value);
    }
}
