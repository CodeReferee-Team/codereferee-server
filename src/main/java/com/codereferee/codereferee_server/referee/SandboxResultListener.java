package com.codereferee.codereferee_server.referee;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxResultListener implements MessageListener {

    private final TaskStatusRepository taskStatusRepository;
    private final TaskStatusPgRepository pgRepository;
    private final PipelineRouter pipelineRouter;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            SandboxResultMessage result = objectMapper.readValue(message.getBody(), SandboxResultMessage.class);
            log.info("[Sandbox] received taskId={} executable={} error={}",
                    result.taskId(), result.executable(), result.errorMessage());

            TaskStatus current = taskStatusRepository.findById(result.taskId())
                    .orElseThrow(() -> new IllegalStateException("TaskStatus not found: " + result.taskId()));

            TaskStatus afterSandbox = result.executable()
                    ? current.withSandboxSuccess()
                    : current.withSandboxFailure(result.errorMessage());

            AgentStep nextStep = pipelineRouter.routeAfterSandbox(afterSandbox);
            TaskStatus routed = afterSandbox.withStep(nextStep);

            taskStatusRepository.save(routed);
            pgRepository.upsert(routed);

            log.info("[Sandbox] taskId={} → routed to {}", routed.taskId(), nextStep);
        } catch (Exception e) {
            log.error("[Sandbox] message processing failed", e);
        }
    }
}
