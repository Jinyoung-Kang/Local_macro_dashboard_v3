-- 2026-10-02 수급 레이더 이력 정리 (한 번만 실행하는 유지보수 SQL)
--
-- [왜] accumulate_history가 upsert만 해서, 장중에 여러 번 수집한 날은 상위 30의 합집합
-- (50~57행)이 쌓였습니다. 밀려난 종목이 옛 수집 시각의 값으로 남은 것입니다. 쓰기는
-- 2026-10-02 커밋(교체 쓰기)부터 고쳐졌고, 이미 쌓인 행은 이 파일로 정리합니다.
--
-- [무엇을] 날짜·조합(시장|투자자|매매|구간)별로 **가장 늦은 수집 시각(collectedAt)의 행만**
-- 남기고 나머지를 지웁니다. 남는 행이 그날 마지막으로 받은 완전한 상위 N입니다.
--
-- [실행 전] make backup. 실행: make db < db/maintenance/2026-10-02-radar-history-dedupe.sql
-- 2026-10-02 실행 결과: 14개 (날짜·조합)에서 284행 삭제, 1,424 → 1,140행.
BEGIN;

WITH combos AS (
    SELECT dataset, obs_date, entity,
           split_part(entity, '|', 1) || '|' || split_part(entity, '|', 2) || '|'
             || split_part(entity, '|', 3) || '|' || split_part(entity, '|', 4) || '|' AS prefix,
           payload->>'collectedAt' AS stamp
    FROM observations
    WHERE dataset = 'radar_ranking'
),
latest AS (
    SELECT obs_date, prefix, max(stamp) AS stamp FROM combos GROUP BY 1, 2
)
DELETE FROM observations o
USING combos c
JOIN latest l ON l.obs_date = c.obs_date AND l.prefix = c.prefix
WHERE o.dataset = c.dataset AND o.obs_date = c.obs_date AND o.entity = c.entity
  AND c.stamp IS DISTINCT FROM l.stamp;

-- 확인: 날짜·조합당 30행을 넘는 곳이 없어야 합니다.
SELECT obs_date, split_part(entity, '|', 1) || '|' || split_part(entity, '|', 2) || '|'
         || split_part(entity, '|', 3) || '|' || split_part(entity, '|', 4) AS combo, count(*)
FROM observations WHERE dataset = 'radar_ranking'
GROUP BY 1, 2 HAVING count(*) > 30;

COMMIT;
