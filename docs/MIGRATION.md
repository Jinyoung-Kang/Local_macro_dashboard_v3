# 구버전 → v2 대응표

구버전([Local-macro-dashboard](https://github.com/Jinyoung-Kang/Local-macro-dashboard),
Python 단일 Streamlit 앱, 약 23,700줄)의 각 파일이 v2 어디로 갔는지 정리했습니다.

## 1. 진입점 · 설정

| 구버전 | v2 | 비고 |
|---|---|---|
| `app.py` (라우팅·인증·CSS) | `frontend/src/app/(dashboard)/layout.tsx`, `frontend/src/components/Sidebar.tsx`, `backend/.../AuthController.java` | 라우팅은 Next.js App Router, 인증은 백엔드 |
| `config.py` 시크릿 로더 | `collector/app/settings.py`, `backend/src/main/resources/application.yml` | 환경변수 우선, TOML은 선택 |
| `config.py` 지표 매핑 | `collector/app/indicators.py` | 표시용 마크다운을 `{key, name, note}`로 분리 |
| `config.py` 해석 테이블 | `backend/.../analytics/AdvancedIndicators.java`, `frontend/.../macro/page.tsx` | 임계치는 백엔드, 표는 화면 |
| `config.py` `LIVE_CLOCK_HTML` | `frontend/src/components/MarketClock.tsx` | iframe + base64 → React 컴포넌트 |
| `collector.py` (CLI·스케줄러) | `collector/app/tasks.py`, `collector/app/main.py` | CLI → REST + APScheduler |

## 2. 저장 계층

| 구버전 | v2 |
|---|---|
| `services/store.py` (SQLite) | `collector/app/store.py` (쓰기) + `backend/.../store/StoreRepository.java` (읽기) |
| `services/datasets.py` | `collector/app/catalog.py` + `backend/.../store/Datasets.java` (테스트가 대조) |
| `store.cached_or_live()` | `backend/.../store/StoreReader.java` |
| `data/dashboard.db` | PostgreSQL `macrodash` |
| `data/collector.lock` | 불필요 (수집기 컨테이너 1개 + APScheduler `max_instances=1`) |

## 3. 수집 서비스

| 구버전 | v2 |
|---|---|
| `services/macro_service.py` | `collector/app/services/market.py` + `collector/app/services/fred.py` |
| `services/advanced_macro_service.py` | 수집: `fred.py` / 해석: `backend/.../analytics/AdvancedIndicators.java` |
| `services/liquidity_service.py` | `collector/app/services/liquidity.py` |
| `services/sector_service.py` | 수집: `collector/app/services/sector.py` / 계산: `backend/.../service/SectorService.java` |
| `services/krx_service.py` | `collector/app/services/krx.py` |
| `services/radar_service.py` | `collector/app/services/radar.py` (+ `kis.py`, `ls.py`) |
| `services/sec_service.py` | 수집: `collector/app/services/sec13f.py` / 분석: `backend/.../service/Sec13FService.java` |
| `services/consensus_service.py` | `backend/.../service/Sec13FService.java#consensus` |
| `services/cot_service.py` | 수집: `collector/app/services/cot.py` / 요약: `backend/.../service/CotService.java` |
| `services/market_scraper_service.py` | `collector/app/services/scraper.py` (HTML 정규식 → JSON 엔드포인트) |
| `services/night_futures_scraper_service.py`, `foreign_index_futures_scraper_service.py` | `collector/app/services/scraper.py` + `tasks.py#_inject_scraped_indices` |
| `services/kis_service.py` | `collector/app/services/kis.py` |
| `services/ls_service.py` | `collector/app/services/ls.py` |
| `services/toss_service.py` | `collector/app/services/toss.py` |
| `services/verification_service.py` | 읽기: `collector/app/main.py#/verify/readings` / 판정: `backend/.../analytics/Verification.java` |
| `services/ai_service.py`, `services/prompts.py` | `backend/.../service/AiService.java`, `SnapshotTextService.java` |
| `services/dashboard_snapshot_service.py` | `backend/.../service/SnapshotTextService.java` |
| `services/http_client.py` | `collector/app/http.py` |
| `services/browser_pool.py` | 제거 (Naver 수집이 렌더링을 쓰지 않습니다) |
| `services/kis_websocket_service.py` | 제외 (구버전에서도 화면에 연결돼 있지 않았습니다) |

## 4. 화면

| 구버전 | v2 |
|---|---|
| `views/macro_view.py` | `frontend/src/app/(dashboard)/macro/page.tsx` |
| `views/liquidity_view.py` | `.../liquidity/page.tsx` |
| `views/sector_view.py` | `.../sector/page.tsx` |
| `views/sec_view.py` | `.../institutions/page.tsx` |
| `views/consensus_view.py` | `.../consensus/page.tsx` |
| `views/cot_view.py` | `.../cot/page.tsx` |
| `views/krx_cot_view.py` | `.../krx/page.tsx` |
| `views/radar_view.py` | `.../radar/page.tsx` |
| `views/data_status_view.py` | `.../status/page.tsx` |
| `views/ai_report_view.py` | `.../ai/report/page.tsx` |
| `views/ai_test_view.py` | `.../connections/AiEngineSection.tsx` (🔌 외부 API 연결 테스트) |
| `views/toss_test_view.py` | `.../connections/TossSection.tsx` (🔌 외부 API 연결 테스트) |

## 5. 테스트

| 구버전 | v2 |
|---|---|
| `tests/test_store.py` | `collector/tests/test_store.py`, `test_tasks.py` |
| `tests/test_regressions.py` | `collector/tests/test_market_cards.py`, `test_krx_futures.py`, `test_radar_chain.py` |
| `tests/test_verification.py` | `backend/.../VerificationTest.java`, `collector/tests/test_radar_chain.py` |
| `tests/test_browser_pool.py` | 불필요 (헤드리스 브라우저 제거) |
| — (신규) | `backend/.../DatasetsParityTest.java` — Python/Java 데이터셋 이름 대조 |
| — (신규) | `backend/.../ApiIntegrationTest.java` — 인증·빈 저장본·수집기 부재 |

## 6. 기능 동등성 확인 목록

이관 시 다음이 유지되는지 확인했습니다.

- [x] 12개 메뉴 전부
- [x] 11개 수집 태스크와 주기(5분/1시간/12시간)
- [x] 읽기 모드 3종(`auto` / `store_only` / `live_only`)
- [x] 수동 새로고침이 저장본을 지우지 않고 기준 시각만 세우는 동작
- [x] 수급 레이더 6단 폴백 체인 + 누적 이력 대체
- [x] 교차 검증 5항목과 판정 4종, 장 시간 게이트
- [x] 추정치/대용 지표 표시와 누적 제외 규칙
- [x] 수집 실패 시 기존 저장본 유지 (`EmptyResult`)
- [x] 13F q1을 q8에서 유도하는 최적화
- [x] SEC 초당 요청 한도 토큰 버킷
- [x] 키가 없을 때 해당 기능만 비활성화
- [x] 거래소 시계와 장 상태 배지(공휴일 포함)

## 7. 데이터 이관

구버전 SQLite(`data/dashboard.db`)의 누적 이력을 옮기고 싶다면:

```bash
# 1) SQLite에서 CSV로 추출
sqlite3 data/dashboard.db <<'SQL'
.headers on
.mode csv
.output timeseries.csv
SELECT dataset, series_id, obs_date, value, updated_at FROM timeseries;
.output observations.csv
SELECT dataset, obs_date, entity, payload, updated_at FROM observations;
SQL

# 2) PostgreSQL로 적재
psql "$DATABASE_URL" -c "\copy timeseries(dataset, series_id, obs_date, value, updated_at) FROM 'timeseries.csv' CSV HEADER"
psql "$DATABASE_URL" -c "\copy observations(dataset, obs_date, entity, payload, updated_at) FROM 'observations.csv' CSV HEADER"
```

`observations.payload`의 한글 키는 v2에서 영문 키로 바뀌었습니다
(`종목코드` → `code`, `순매수대금(억)` → `netAmountEok` 등). 과거 이력을 화면에서
쓰려면 적재 전에 키를 변환해야 합니다. 변환 없이 적재하면 **읽히지 않을 뿐**
기존 데이터가 깨지지는 않습니다.

`snapshots`는 이관하지 않아도 됩니다 — 수집기가 한 번 돌면 다시 채워집니다.

---

## 구버전에만 있던 것 (이관 후 확인)

포팅 과정에서 빠졌다가 되살린 항목입니다.

| 구버전 | v2 | 비고 |
|---|---|---|
| `services/night_futures_scraper_service.py`<br>코스피200 야간선물 (CME 연계) | `services/scraper.py`의 `kospi200_night` | **한동안 빠져 있었습니다.** 구버전은 TradingView HTML 정규식 → Investing.com → KODEX 프록시 순서였습니다. v2는 앞 두 단계를 Symbol Scanner JSON 하나로 바꾸고, 마지막 KODEX 프록시는 그대로 살렸습니다(반드시 추정치 표시) |
| `services/foreign_index_futures_scraper_service.py`<br>닛케이225·항셍 선물 | `tasks.py`의 `_inject_scraped_indices` | Symbol Scanner JSON으로 대체 |
| `services/browser_pool.py`<br>헤드리스 Chromium 렌더링 | 없음 (의도적) | Naver iframe은 서버가 완성된 HTML을 줍니다. 브라우저 의존성을 없애 이미지가 가벼워집니다. 다만 Naver가 비면 원인을 구분해 보여 줍니다 — "표가 없습니다"(차단·JS 요구)면 이 판단을 다시 봐야 한다는 신호입니다 |
| `services/kis_websocket_service.py` | 없음 (의도적) | 구버전에서도 어디에서도 호출되지 않는 죽은 코드였습니다 |

구버전의 11개 수집 태스크는 이름·주기·의미가 모두 동일합니다.

---

## v2에서 새로 생긴 것 (구버전에 대응이 없음)

| 화면 / 기능 | 어디에 |
|---|---|
| 🧭 시장 국면 판정 | `analytics/Regime.java` |
| 🔗 지표 상관관계 | `analytics/Correlation.java` |
| 🧬 기관 13F 스타일·위험 (옛 이름: 구루 포트폴리오 분석) | `analytics/GuruStyle.java` · `PortfolioRisk.java` |
| 🩺 종목 스코어카드 | `analytics/Scorecard.java` |
| COT 극단 포지션 백테스트 | `analytics/CotExtremes.java` |
| 환율 겹쳐 보기 | `analytics/FxIndex.java` · 수집 태스크 `fx_history` |
| 외국인·기관 공통 수급 | `analytics/SupplyConsensus.java` |
| 13F 종목명 → 티커 매핑 | `collector/app/equities.py` · 수집 태스크 `equity_history` |
| 전체 원본 데이터 복사 | `service/SnapshotTextService.java` |

수집 태스크는 `fx_history`·`equity_history` 둘이 늘어 13개가 됐고, 이후 국내 공공 API 태스크 셋(`kr_holidays`·`fsc_prices`·`dart_fundamentals`, [DATA_SOURCES.md](DATA_SOURCES.md) 7절)이 더해져 16개, 토스증권 투자자별 매매 둘(`toss_market_flows`·`toss_stock_flows`)이 더해져 18개, 레이더 폴백용 `toss_radar_universe`가 더해져 **19개**입니다.
