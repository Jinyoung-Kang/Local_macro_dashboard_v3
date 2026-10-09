-- =============================================================================
-- V3__regime_log.sql
-- 국면 판정 라이브 기록 — 하루 한 건, 그날 화면에 보인 판정과 근거(입력값·입력별 기준일).
--
-- **백엔드가 쓰는 첫 테이블입니다**(ADR 0008). 다른 테이블은 수집기가 쓰고, 이 테이블은
-- 백엔드(RegimeRecorder)만 씁니다. 스키마 파일은 지금처럼 이 폴더 한 곳에 두고 수집기가
-- 기동할 때 적용합니다.
--
-- 기록은 추가만 합니다. 고치거나 지우는 경로를 두지 않습니다 — 판정 성과를 공개할 때
-- "나중에 고친 판정이 아니다"를 이 테이블이 보증합니다. 같은 날짜는 한 건뿐입니다(기본키).
-- =============================================================================

CREATE TABLE IF NOT EXISTS regime_log (
    verdict_date DATE        PRIMARY KEY,           -- 기록한 날(KST)
    code         TEXT        NOT NULL,              -- EXPANSION·LATE·RECOVERY·CONTRACTION·UNKNOWN
    label        TEXT        NOT NULL,
    inputs       JSONB       NOT NULL,              -- {curve10y3m:{value,asOf}, nfci:…, hyOas:…, liquidity4w:…}
    missing      TEXT[]      NOT NULL DEFAULT '{}', -- 판정 불가 사유(빠지거나 오래된 입력)
    rule_version TEXT        NOT NULL,              -- 판정 규칙(임계치) 판
    computed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
