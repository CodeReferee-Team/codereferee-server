package com.codereferee.codereferee_server.infrastructure.persistence;

import com.codereferee.codereferee_server.domain.validation.AgentStep;
import com.codereferee.codereferee_server.domain.validation.ChaosOptions;
import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class TaskStatusPgRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 7, 12, 0);
    private JdbcTemplate jdbc;
    private TaskStatusPgRepository repository;

    @BeforeEach
    void setup() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.execute(Files.readString(Path.of("db", "schema.sql")));
        jdbc.update("DELETE FROM task_status");
        repository = new TaskStatusPgRepository(jdbc, new ObjectMapper());
    }

    void insert(String id, String step, int ageMinutes) {
        jdbc.update("INSERT INTO task_status(task_id, current_agent, updated_at) VALUES (?, ?, ?)",
                id, step, Timestamp.valueOf(NOW.minusMinutes(ageMinutes)));
    }

    @Test
    void staleCutoffsAreTypedAndDifferForQueuedAndRunningInPostgres() {
        insert("queued-old", "QUEUED", 70);
        insert("queued-recent", "QUEUED", 45);
        insert("running-old", "BASELINE", 45);
        insert("running-recent", "BASELINE", 15);
        insert("terminal", "PASSED", 120);
        insert("queued-boundary", "QUEUED", 60);
        insert("running-boundary", "CHAOS", 30);

        assertThat(repository.findStale(NOW.minusMinutes(60), NOW.minusMinutes(30), 10))
                .extracting(status -> status.taskId()).containsExactly("queued-old", "running-old");
        assertThat(repository.findStale(NOW.minusMinutes(60), NOW.minusMinutes(30), 1))
                .extracting(status -> status.taskId()).containsExactly("queued-old");
    }

    @Test
    void terminalReportsAreStoredAsJsonbAndRoundTripThroughHistory() {
        var queued = TaskStatus.queued("reported-job", "https://github.com/example/repo.git",
                "main", "sha", ChaosOptions.of("suite_deep", "quickbyte-demo"), NOW);
        repository.upsert(queued);
        Map<String, Object> reports = Map.of("execution_result", Map.of(
                "observation_status", "observed", "exit_code", 0,
                "chaos_observation", Map.of("recovered", true)),
                "judge_report", Map.of("reason", "복구 시간 초과"));
        repository.upsert(queued.withAiResult(AgentStep.FAILED, false, "복구 시간 초과", reports));

        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(ai_reports) FROM task_status WHERE task_id = ?",
                String.class, "reported-job")).isEqualTo("object");
        var stored = repository.findByRepositoryAndCommit(queued.repositoryUrl(), "sha").get(0);
        assertThat(stored.currentAgent()).isEqualTo(AgentStep.FAILED);
        assertThat(stored.aiReports()).isEqualTo(reports);
        assertThat(stored.errorMessage()).isEqualTo("복구 시간 초과");
        assertThat(stored.chaosOptions()).isEqualTo(queued.chaosOptions());
    }
}
