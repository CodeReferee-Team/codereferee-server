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
    chaos_mode      VARCHAR(64),  -- 샌드박스 어휘. BE는 형식만 보고 보존한다
    deployment_profile VARCHAR(64),
    ai_reports      JSONB
);

-- 재검사 시 이전 이력 조회용 (GET /api/validations/history)
CREATE INDEX IF NOT EXISTS idx_task_status_repo_commit
    ON task_status (repository_url, commit_sha, updated_at DESC);

-- 결과가 도착하지 않은 요청 정리용 (StaleValidationSweeper).
-- 시간이 지나면 종결 상태가 대부분이므로 부분 인덱스로 비종결 행만 담는다.
CREATE INDEX IF NOT EXISTS idx_task_status_stale
    ON task_status (updated_at)
    WHERE current_agent NOT IN ('PASSED', 'FAILED', 'ERROR');

-- 기존 DB 적용용 (ddl-auto: none이라 수동)
ALTER TABLE task_status ADD COLUMN IF NOT EXISTS chaos_mode VARCHAR(64);
ALTER TABLE task_status ADD COLUMN IF NOT EXISTS deployment_profile VARCHAR(64);
