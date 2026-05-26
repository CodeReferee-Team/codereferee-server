package com.codereferee.codereferee_server.referee;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class TaskStatusPgRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public void upsert(TaskStatus status) {
        String aiReportsJson = toJson(status.aiReports());
        jdbcTemplate.update("""
                INSERT INTO task_status
                    (task_id, current_agent, is_executable, iteration_count, error_message, updated_at,
                     repository_url, branch, commit_sha, ai_reports)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (task_id) DO UPDATE SET
                    current_agent   = EXCLUDED.current_agent,
                    is_executable   = EXCLUDED.is_executable,
                    iteration_count = EXCLUDED.iteration_count,
                    error_message   = EXCLUDED.error_message,
                    updated_at      = EXCLUDED.updated_at,
                    repository_url  = EXCLUDED.repository_url,
                    branch          = EXCLUDED.branch,
                    commit_sha      = EXCLUDED.commit_sha,
                    ai_reports      = EXCLUDED.ai_reports
                """,
                status.taskId(),
                status.currentAgent().name(),
                status.executable(),
                status.iterationCount(),
                status.errorMessage(),
                Timestamp.valueOf(status.updatedAt()),
                status.repositoryUrl(),
                status.branch(),
                status.commitSha(),
                aiReportsJson
        );
    }

    private String toJson(Map<String, Object> reports) {
        if (reports == null || reports.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(reports);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new RuntimeException("Failed to serialize AI reports to JSON", e);
        }
    }
}
