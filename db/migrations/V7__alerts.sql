-- =============================================================================
-- V7__alerts.sql
-- 알림 — 규칙(사용자별)·사건(전역 사실)·알림함 항목(누구에게). 백엔드가 쓰는 테이블(ADR 0008, 0011).
--
-- - alert_events.event_key가 유일 — 같은 데이터를 몇 번 평가해도 같은 사건은 한 번만 생깁니다.
-- - alert_deliveries는 (사건, 사용자, 채널)이 유일 — 같은 사건이 같은 사람의 알림함에 두 번 들어가지 않습니다.
--   지금 채널은 화면 알림함('inbox')뿐입니다(명세 D2). 외부 채널을 붙일 때 발송 상태 열을 더합니다.
-- - 관심 기관·관심 종목은 규칙의 params(cik·code)로 둡니다(따로 목록 테이블을 두지 않음). 수집기는 종목 규칙의
--   코드를 읽어 종목별 수급 수집 범위에 더합니다 — 테이블 소유 규칙의 유일한 교차 읽기입니다.
-- =============================================================================

CREATE TABLE IF NOT EXISTS alert_rules (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind       TEXT        NOT NULL CHECK (kind IN ('REGIME_CHANGE', 'FOREIGN_STREAK', 'INSTITUTION_NEW')),
    params     JSONB       NOT NULL DEFAULT '{}',
    channel    TEXT        NOT NULL DEFAULT 'inbox',
    enabled    BOOLEAN     NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS alert_rules_kind ON alert_rules (kind) WHERE enabled;

CREATE TABLE IF NOT EXISTS alert_events (
    id          BIGSERIAL   PRIMARY KEY,
    kind        TEXT        NOT NULL,
    event_key   TEXT        NOT NULL UNIQUE,
    occurred_on DATE        NOT NULL,
    title       TEXT        NOT NULL,
    body        TEXT        NOT NULL,
    payload     JSONB       NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS alert_events_occurred ON alert_events (occurred_on DESC);

CREATE TABLE IF NOT EXISTS alert_deliveries (
    id         BIGSERIAL   PRIMARY KEY,
    event_id   BIGINT      NOT NULL REFERENCES alert_events (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    rule_id    BIGINT      REFERENCES alert_rules (id) ON DELETE SET NULL,
    channel    TEXT        NOT NULL DEFAULT 'inbox',
    late       BOOLEAN     NOT NULL DEFAULT false,   -- 사건 발생 3일 뒤에 들어간 '지난 사건'(읽지 않은 수에 세지 않음)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at    TIMESTAMPTZ,
    UNIQUE (event_id, user_id, channel)
);
CREATE INDEX IF NOT EXISTS alert_deliveries_inbox ON alert_deliveries (user_id, created_at DESC);
