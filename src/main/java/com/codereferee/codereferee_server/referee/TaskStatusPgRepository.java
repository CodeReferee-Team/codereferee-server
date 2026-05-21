package com.codereferee.codereferee_server.referee;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

@Repository
@RequiredArgsConstructor
public class TaskStatusPgRepository {

    private final JdbcTemplate jdbcTemplate;

    public void upsert(TaskStatus status) {
        jdbcTemplate.update("""
                INSERT INTO task_status
                    (task_id, current_agent, is_executable, iteration_count, error_message, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (task_id) DO UPDATE SET
                    current_agent   = EXCLUDED.current_agent,
                    is_executable   = EXCLUDED.is_executable,
                    iteration_count = EXCLUDED.iteration_count,
                    error_message   = EXCLUDED.error_message,
                    updated_at      = EXCLUDED.updated_at
                """,
                status.taskId(),
                status.currentAgent().name(),
                status.executable(),
                status.iterationCount(),
                status.errorMessage(),
                Timestamp.valueOf(status.updatedAt())
        );
    }
}
