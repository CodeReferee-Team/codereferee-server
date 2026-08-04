package com.codereferee.codereferee_server.infrastructure.redis;

import com.codereferee.codereferee_server.domain.validation.AgentStep;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Optional;

public record ProgressEventMessage (
        @JsonProperty("type") String type,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("step") String step,
        @JsonProperty("round") Integer round, // REFINING 단계에서는 JSON에 필드가 없어 null이 들어온다.
        @JsonProperty("max_rounds") Integer maxRounds,
        @JsonProperty("detail") String detail,
        @JsonProperty("timestamp") String timestamp // 로그 저장용이니 String
){
    public Optional<AgentStep> resolveStep() {
        if (step == null) return Optional.empty();
        try {
            AgentStep parsed = AgentStep.valueOf(step);
            return parsed.isTerminal() ? Optional.empty() : Optional.of(parsed); // 예외로 빼지 않고 Optional.empty()로 처리
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
