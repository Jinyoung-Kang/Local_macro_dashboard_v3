-- =============================================================================
-- V6__usage_counters.sql
-- 사용자별 하루 사용량(예: AI 리포트) — 유료 API 비용 상한(명세 B7). 백엔드가 쓰는 테이블(ADR 0008).
-- (사용자, KST 날짜, 종류)마다 한 행이고, INSERT … ON CONFLICT로 원자적으로 늘립니다.
-- =============================================================================

CREATE TABLE IF NOT EXISTS usage_counters (
    user_id BIGINT  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    day     DATE    NOT NULL,
    kind    TEXT    NOT NULL,
    count   INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, day, kind)
);
