-- CodeReferee Backend DB 스키마 (참조용 — ddl-auto: none이므로 수동 적용)
CREATE TABLE IF NOT EXISTS task_status (
    task_id         VARCHAR(64) PRIMARY KEY,
    current_agent   VARCHAR(20)  NOT NULL,  -- AgentStep: QUEUED..REFINING, PASSED/FAILED/ERROR
    is_executable   BOOLEAN      NOT NULL DEFAULT FALSE,
    iteration_count INT          NOT NULL DEFAULT 0,
    error_message   TEXT,
    updated_at      TIMESTAMP    NOT NULL,
    repository_url  TEXT,
    branch          VARCHAR(255),
    commit_sha      VARCHAR(64),
    ai_reports      JSONB
);

-- 재검사 시 이전 이력 조회용 (GET /api/validations/history)
CREATE INDEX IF NOT EXISTS idx_task_status_repo_commit
    ON task_status (repository_url, commit_sha, updated_at DESC);
