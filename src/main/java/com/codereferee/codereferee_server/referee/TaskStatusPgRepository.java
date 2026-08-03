package com.codereferee.codereferee_server.referee;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
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

    /** 같은 repo+commit의 과거 검증 이력 (최신순, 최대 20건) — 재검사 시 이전 로그 제공용 */
    public List<TaskStatus> findByRepositoryAndCommit(String repositoryUrl, String commitSha) {
        return jdbcTemplate.query("""
                SELECT task_id, current_agent, is_executable, iteration_count, error_message, updated_at,
                       repository_url, branch, commit_sha, ai_reports
                FROM task_status
                WHERE repository_url = ? AND commit_sha = ?
                ORDER BY updated_at DESC
                LIMIT 20
                """, rowMapper(), repositoryUrl, commitSha);
    }

    private RowMapper<TaskStatus> rowMapper() {
        return (ResultSet rs, int rowNum) -> new TaskStatus(
                rs.getString("task_id"),
                AgentStep.valueOf(rs.getString("current_agent")),
                rs.getBoolean("is_executable"),
                rs.getInt("iteration_count"),
                rs.getString("error_message"),
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null,
                rs.getString("repository_url"),
                rs.getString("branch"),
                rs.getString("commit_sha"),
                fromJson(rs.getString("ai_reports"))
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

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new RuntimeException("Failed to deserialize AI reports from JSON", e);
        }
    }
}
