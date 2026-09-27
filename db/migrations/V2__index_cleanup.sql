-- =============================================================================
-- V2__index_cleanup.sql
-- 기본키와 겹치는 인덱스를 지우고, payload GIN 인덱스를 수급 레이더 행으로 좁힙니다.
--
-- 1. idx_timeseries_lookup (dataset, series_id, obs_date)
--    기본키와 컬럼·순서가 똑같습니다. 같은 내용을 두 번 저장하고 두 번 갱신했습니다.
-- 2. idx_observations_lookup (dataset, obs_date DESC)
--    기본키 (dataset, obs_date, entity)의 앞 두 컬럼과 같습니다. B-tree는 거꾸로도 읽을 수
--    있어 "최신 날짜 순" 조회도 기본키가 처리합니다.
-- 3. idx_observations_radar_filter (payload GIN, 표 전체)
--    payload 포함 조건(@>)으로 거르는 곳은 수급 레이더(radar_ranking)뿐인데, 하루 약 3천 행씩
--    쌓이는 금융위 시세(fsc_price)까지 모든 행을 색인했습니다. 레이더 행만 색인하는 부분
--    인덱스로 바꿉니다.
--
-- 적용은 수집기가 기동할 때 합니다(app/migrations.py). 이미 적용한 파일은 고치지 말고
-- 새 번호로 추가하세요. 여러 번 실행해도 결과가 같도록 IF EXISTS / IF NOT EXISTS를 씁니다.
-- =============================================================================

DROP INDEX IF EXISTS idx_timeseries_lookup;
DROP INDEX IF EXISTS idx_observations_lookup;
DROP INDEX IF EXISTS idx_observations_radar_filter;

CREATE INDEX IF NOT EXISTS idx_observations_radar_payload
    ON observations USING GIN (payload jsonb_path_ops)
    WHERE dataset = 'radar_ranking';
