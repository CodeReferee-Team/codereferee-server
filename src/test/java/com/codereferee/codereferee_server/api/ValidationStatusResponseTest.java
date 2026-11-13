package com.codereferee.codereferee_server.api;

import com.codereferee.codereferee_server.domain.validation.AgentStep;
import com.codereferee.codereferee_server.domain.validation.ChaosOptions;
import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 폴링 응답의 JSON 모양을 고정한다.
 *
 * @JsonUnwrapped가 기대대로 펼쳐지지 않으면 응답이 {"status": {...}} 로 한 겹 감싸지고
 * 화면의 모든 필드가 한꺼번에 사라진다. 눈으로는 "그냥 안 나온다"로만 보이는 실패라
 * 테스트로 고정해 둔다.
 */
class ValidationStatusResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private TaskStatus task(AgentStep step) {
        return new TaskStatus("t1", step, false, 0, null,
                LocalDateTime.of(2026, 11, 14, 10, 0), LocalDateTime.of(2026, 11, 14, 10, 1),
                "https://github.com/o/r", "main", "abc", null, ChaosOptions.NONE, Map.of());
    }

    private JsonNode json(TaskStatus status, Integer queuedAhead) throws Exception {
        return objectMapper.readTree(
                objectMapper.writeValueAsString(ValidationStatusResponse.of(status, queuedAhead)));
    }

    @Test
    void unwrapsTaskStatusFieldsToTheTopLevel() throws Exception {
        JsonNode node = json(task(AgentStep.QUEUED), 3);

        // 감싸이면 여기서 깨진다.
        assertThat(node.has("status")).isFalse();
        assertThat(node.get("taskId").asText()).isEqualTo("t1");
        assertThat(node.get("currentAgent").asText()).isEqualTo("QUEUED");
        assertThat(node.get("repositoryUrl").asText()).isEqualTo("https://github.com/o/r");
        assertThat(node.has("createdAt")).isTrue();
        assertThat(node.has("chaosOptions")).isTrue();
        assertThat(node.get("queuedAhead").asInt()).isEqualTo(3);
    }

    @Test
    void queuedAheadIsNullWhenItDoesNotApply() throws Exception {
        JsonNode node = json(task(AgentStep.BASELINE), null);

        // 키는 있고 값만 null이다. 화면은 null을 "표시하지 않음"으로 읽는다.
        assertThat(node.has("queuedAhead")).isTrue();
        assertThat(node.get("queuedAhead").isNull()).isTrue();
        assertThat(node.get("currentAgent").asText()).isEqualTo("BASELINE");
    }
}
