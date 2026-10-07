package com.codereferee.codereferee_server.infrastructure.persistence;

import com.codereferee.codereferee_server.domain.validation.AgentStep;
import com.codereferee.codereferee_server.domain.validation.ChaosOptions;
import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Postgres(jsonb 스키마)에 대한 upsert 왕복 검증.
 *
 * 회귀 방지 대상: ai_reports는 jsonb 칼럼인데 캐스트 없이 String을 넣으면
 * "column ai_reports is of type jsonb but expression is of type character varying"로 깨진다.
 * E2E 테스트는 상태를 Redis에서 읽고 upsert 예외를 consumer가 삼켜 이 버그를 놓쳤다.
 * 여기서는 PG 왕복을 직접 본다.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TaskStatusPgRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withCopyFileToContainer(MountableFile.forHostPath("db/schema.sql"),
                    "/docker-entrypoint-initdb.d/schema.sql");

    @Autowired
    JdbcTemplate jdbcTemplate;

    TaskStatusPgRepository repository;

    @BeforeEach
    void setUp() {
        repository = new TaskStatusPgRepository(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void upsertRoundTripsNonNullAiReportsAndEmail() {
        TaskStatus terminal = TaskStatus
                .queued("t-roundtrip", "https://github.com/o/r", "main", "sha-rt",
                        "dev@example.com", ChaosOptions.NONE, LocalDateTime.now())
                .withAiResult(AgentStep.FAILED, false, "fail",
                        Map.of("judge_report", Map.of("reason_category", "test_failure")));

        // 캐스트가 없으면 여기서 jsonb 타입 오류로 깨진다.
        repository.upsert(terminal);

        List<TaskStatus> found = repository.findByRepositoryAndCommit("https://github.com/o/r", "sha-rt");
        assertThat(found).hasSize(1);
        TaskStatus got = found.get(0);
        assertThat(got.email()).isEqualTo("dev@example.com");
        assertThat(got.aiReports()).containsKey("judge_report");
    }

    @Test
    void upsertAcceptsNullAiReports() {
        TaskStatus queued = TaskStatus.queued("t-null", "https://github.com/o/r2", "main", "sha-null",
                null, ChaosOptions.NONE, LocalDateTime.now());

        repository.upsert(queued);

        List<TaskStatus> found = repository.findByRepositoryAndCommit("https://github.com/o/r2", "sha-null");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).aiReports()).isNull();
        assertThat(found.get(0).email()).isNull();
    }

    @Test
    void findStaleReturnsOldNonTerminalRows() {
        // 회귀 방지: 예전 쿼리는 CASE 안의 파라미터 타입을 PG가 추론하지 못해
        // "operator does not exist: timestamp without time zone < text"로 깨졌다.
        LocalDateTime old = LocalDateTime.now().minusHours(2);
        LocalDateTime recent = LocalDateTime.now();

        repository.upsert(TaskStatus.queued("stale-queued", "https://github.com/o/s1", "main", "c1",
                null, ChaosOptions.NONE, old));
        repository.upsert(TaskStatus.queued("fresh-queued", "https://github.com/o/s2", "main", "c2",
                null, ChaosOptions.NONE, recent));
        repository.upsert(TaskStatus.queued("done", "https://github.com/o/s3", "main", "c3",
                        null, ChaosOptions.NONE, old)
                .withAiResult(AgentStep.PASSED, true, null, Map.of()));

        // 쿼리가 던지지 않고(타입 버그 회귀) 오래된 비종결 QUEUED만 돌려줘야 한다.
        List<TaskStatus> stale = repository.findStale(
                LocalDateTime.now().minusMinutes(30), LocalDateTime.now().minusMinutes(10), 10);

        List<String> ids = stale.stream().map(TaskStatus::taskId).toList();
        assertThat(ids).contains("stale-queued");
        assertThat(ids).doesNotContain("fresh-queued", "done");
    }
}
