-- =============================================================================
-- V5__email_tokens.sql
-- 메일 링크 토큰(가입 인증·비밀번호 재설정) — 백엔드가 쓰는 테이블(ADR 0008, 0010).
-- 링크의 토큰 원문은 저장하지 않고 SHA-256만 둡니다. 한 번 쓰면 used_at이 찍히고, 만료가 지나면 거절합니다.
-- 계정을 지우면 함께 지워집니다.
-- =============================================================================

CREATE TABLE IF NOT EXISTS email_tokens (
    token_hash BYTEA       PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose    TEXT        NOT NULL CHECK (purpose IN ('VERIFY', 'RESET')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS email_tokens_user ON email_tokens (user_id, purpose);
