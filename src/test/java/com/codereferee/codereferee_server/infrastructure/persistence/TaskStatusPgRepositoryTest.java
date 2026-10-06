package com.codereferee.codereferee_server.infrastructure.persistence;

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
}
