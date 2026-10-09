-- =============================================================================
-- V4__accounts.sql
-- 계정·세션·감사 기록 — 백엔드가 쓰는 테이블(ADR 0008, 0010).
--
-- - users: 이메일(대소문자 무시 유일)·비밀번호 해시(Argon2id)·역할(OWNER·MEMBER)·등급(FREE·PRO).
--   계정을 지우면 행을 지웁니다(개인정보 즉시 파기). 세션은 함께 지워지고, 감사 기록은 사용자 번호만 비웁니다.
-- - user_sessions: 쿠키 토큰의 SHA-256만 둡니다(원문 없음). 로그아웃·비밀번호 변경·등급 변경이 즉시 반영되고
--   재시작해도 유지됩니다. 접속 IP는 저장하지 않습니다.
-- - audit_log: 로그인 성공·실패, 비밀번호 변경, 역할·등급 변경, 계정 삭제. 비밀번호·토큰 값은 남기지 않습니다.
-- =============================================================================

CREATE TABLE IF NOT EXISTS users (
    id                BIGSERIAL   PRIMARY KEY,
    email             TEXT        NOT NULL,
    password_hash     TEXT        NOT NULL,
    role              TEXT        NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
    tier              TEXT        NOT NULL DEFAULT 'FREE' CHECK (tier IN ('FREE', 'PRO')),
    email_verified_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS users_email_lower ON users (lower(email));

CREATE TABLE IF NOT EXISTS user_sessions (
    id           UUID        PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash   BYTEA       NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    user_agent   TEXT
);
CREATE INDEX IF NOT EXISTS user_sessions_user ON user_sessions (user_id);

CREATE TABLE IF NOT EXISTS audit_log (
    id      BIGSERIAL   PRIMARY KEY,
    user_id BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    action  TEXT        NOT NULL,
    at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    detail  JSONB       NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS audit_log_at ON audit_log (at DESC);
