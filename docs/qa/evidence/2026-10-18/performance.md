# 2026-10-18 QA 성능 측정 (QA 스택)

- 환경: 같은 맥(8코어·16GB), QA 스택 `macrodash_qa`(외부 호출 차단 프록시), 2026-10-12 백업 복원본(저장본 68·관측 30,876행),
  수집 스케줄 꺼짐, 읽기 모드 auto.
- 절차(부하): 백엔드 재시작 → 동시 50명 30초 예열(버림) → `scripts/qa/k6/api-load.js`(16경로 무작위, 요청 사이 0.1초) 60초 × 3회,
  중앙값. 모든 회차 실패 요청 0.
- 기준선 `097d98d`과 브랜치는 같은 DB 볼륨에서 이미지만 바꿔 띄웠습니다. '캐시 후'는 `141a6b5`(13F 포트폴리오 응답 캐시)입니다.

## 전체

| 동시 사용자 | 기준선 p95 · 중앙값 · 처리량 | 브랜치(캐시 전) | 캐시 후 |
|---|---|---|---|
| 50명 | 19.7ms · 2.7ms · 467.5건/초 (p95 3회 [19.9, 18.6, 19.7]) | 23.2ms · 3.3ms · 459.4건/초 (p95 3회 [22.9, 25.1, 23.2]) | 16.9ms · 2.4ms · 471.0건/초 (p95 3회 [17.3, 16.9, 16.7]) |
| 10명 | 16.0ms · 2.3ms · 94.2건/초 (p95 3회 [15.8, 16.0, 16.3]) | 15.0ms · 2.5ms · 94.1건/초 (p95 3회 [14.9, 15.0, 15.2]) | 16.3ms · 2.2ms · 94.2건/초 (p95 3회 [16.3, 16.5, 16.1]) |

## 경로별 p95 (동시 50명, 3회 중앙값, ms)

| 경로 | 기준선 | 브랜치(캐시 전) | 캐시 후 |
|---|---|---|---|
| `/api/sec13f_portfolio_cik_0001608046_quarters_8_topN_30` | 9.3 | 28.4 | 5.7 |
| `/api/macro_overview` | 39.9 | 52.9 | 32.0 |
| `/api/sector_momentum` | 22.7 | 33.4 | 16.2 |
| `/api/macro_risk` | 21.6 | 31.7 | 18.6 |
| `/api/status` | 32.0 | 40.3 | 28.8 |
| `/api/liquidity_years_3` | 10.1 | 15.8 | 8.4 |
| `/api/snapshot_text` | 6.7 | 10.2 | 5.7 |
| `/api/kr_investor_flows` | 7.9 | 11.2 | 7.4 |
| `/api/sec13f_consensus_minHolders_2` | 6.2 | 9.3 | 6.3 |
| `/api/radar_ranking` | 8.7 | 11.0 | 7.5 |
| `/api/analytics_regime_years_5` | 6.9 | 9.0 | 6.2 |
| `/api/guru_profiles` | 5.8 | 7.8 | 5.1 |
| `/api/stock_scorecard_symbol_AAPL_benchmark_SPY_years_1` | 6.1 | 7.9 | 5.3 |
| `/api/guru_similarity` | 6.1 | 7.9 | 5.7 |
| `/api/krx_futures_days_60` | 7.0 | 8.8 | 6.4 |
| `/api/cot_extremes_name_S_26P_20500_20E_Mini_percentile_95_lo` | 8.7 | 10.4 | 7.3 |

## 캐시가 빈 첫 요청 (백엔드 재시작 직후 경로마다 1번, 5회 중앙값, ms)

| 경로 | 기준선 | 브랜치(캐시 전) |
|---|---|---|
| `/api/sec13f/consensus?minHolders=2` | 680 | 633 |
| `/api/sec13f/new-buys?minBuyers=2` | 41 | 43 |
| `/api/sec13f/portfolio?cik=0001608046&quarters=8&topN=30` | 13 | 22 |
| `/api/sec13f/portfolio?cik=0001350694&quarters=8&topN=30` | 10 | 38 |
| `/api/guru/profiles` | 30 | 32 |
| `/api/guru/risk?cik=0001608046&benchmark=SPY&years=1` | 301 | 320 |
| `/api/stock/scorecard?symbol=AAPL&benchmark=SPY&years=1` | 93 | 124 |
| `/api/stock/scorecard?symbol=NVDA&benchmark=SPY&years=3` | 67 | 77 |
| `/api/sector/rotation?period=1M` | 42 | 35 |
| `/api/analytics/correlation?x=krx:futures&y=etf:SPY&window=60&years=1&mode=change` | 36 | 28 |
| `/api/kr/investor-flows` | 27 | 36 |
| `/api/krx/spot-futures` | 21 | 24 |
| `/api/snapshot/text` | 256 | 274 |
| `/api/ai/snapshot-text` | 145 | 9 |
| `/api/status/issues` | 101 | 100 |
| `/api/macro/overview` | 43 | 53 |

- `/api/ai/snapshot-text` 145 → 9ms는 측정 순서상 바로 앞의 `/api/snapshot/text`가 캐시를 데운 덕입니다(QA-097: 같은 캐시 항목).
- `/api/stock/scorecard` 1년 93 → 124ms는 12개월 모멘텀을 전체 저장본으로 계산하기 때문입니다(QA-088). 이후 요청은 캐시입니다.
- 13F 포트폴리오 13 → 22ms(국민연금)·10 → 38ms(브리지워터)는 비중 추이를 증권 단위로 묶기 때문입니다(QA-112·121).
  이 경로만 응답 캐시를 거치지 않아 부하 중 다른 요청까지 늦췄고, `141a6b5`에서 캐시에 붙였습니다.

## 화면 번들 (`.next/static`의 JS, 컨테이너 안에서)

| | 파일 수 | 원본 | gzip(이어 붙여) | next · react |
|---|---|---|---|---|
| 기준선 | 36 | 1,324,059B | 398,921B | 16.4.0 19.3.0 |
| 브랜치 | 36 | 1,327,944B | 394,837B | 16.4.2 19.3.1 |

## 측정에서 틀릴 뻔한 것

- 처음에는 이미지를 띄운 직후 한 번씩 재서 브랜치 50명 p95가 125.2ms로 나왔습니다(기준선 19.4ms). 바로 다시 재면 66.1 → 29.7ms로
  줄었습니다. 기동 직후의 예열(JIT·GC) 차이였고, 그래서 위의 '재시작 → 예열 → 3회' 절차로 두 판을 다시 쟀습니다.
- 기준선 첫 측정 중(03:48 UTC)에 auto 모드가 깨운 수집이 저장본 4개를 갱신해 응답 캐시가 한 번 통째로 버려졌습니다.
  절차를 맞춘 측정에서는 측정 중 저장본 갱신이 없었습니다.
