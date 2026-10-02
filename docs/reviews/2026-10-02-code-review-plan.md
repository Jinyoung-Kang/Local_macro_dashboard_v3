# 2026-10-02 코드 리뷰 결과와 개선 계획

> 반영 결과: [2026-10-02-code-review-results.md](2026-10-02-code-review-results.md)

- 대상: `main@0bbf67b` (백엔드 12.9k · 수집기 9.7k · 화면 12.3k 줄)
- 방법: 보안 · 백엔드 · 수집기 · 화면 · 운영/DB 다섯 관점에서 각각 읽기 전용 리뷰를 돌리고,
  상위 항목은 소스와 **실제 DB·API 값**으로 다시 확인했다. 성능은 변경 전 수치를 먼저 쟀다
  (`scripts/bench-api.sh`, 아래 §6).
- 기준: 심각도 = 데이터 손실·오염 > 보안 > 틀린 숫자 > 멈춤·대기 > 성능 > 구조.

## 0. 한 줄 요약

치명(무조건 파괴) 결함은 없다. 그러나 **다시 받을 수 없는 수급 이력이 이미 오염되고 있고**(§1-1),
외부 소스가 필드 하나만 빼도 **40일치 KRX 미결제약정 이력이 0으로 덮이며**(§1-2), 화면 두 곳이
**같은 이름으로 다른 숫자**를 보여 준다(§3-1, §3-2). 보안은 LAN에서 누구나 로그인을 영구히 막을 수
있는 전역 잠금 하나가 중간 등급이고 나머지는 낮음. 구조는 ADR 0001/0004가 대체로 지켜지고 있어
**버그를 고치면서 생기는 이음새만** 떼어 낸다.

## 1. P0 — 데이터 손실·오염 (수집기)

| # | 문제 | 근거 | 고칠 방법 |
|---|---|---|---|
| 1-1 | **수급 레이더 이력이 하루에 top-30의 합집합으로 쌓인다.** `accumulate_history`가 upsert만 해서 장중 여러 번 수집하면 빠진 종목이 옛 값으로 남는다. 백업용 `read_from_history`는 그 55~57행을 다시 정렬해 "그날의 top-30"이라고 돌려준다. | `collector/app/services/radar.py:724-767`. 실제 DB: 09-29 55행(수집 시각 2종), 09-28 57행, 09-16~18 50~52행 (한 조합당 30행이어야 함) | 조합(시장·투자자·매매·구간)+날짜 단위로 **한 트랜잭션에서 지우고 다시 쓰기** (`store.replace_observations`). 실패하는 테스트부터 작성 |
| 1-2 | `read_from_history`가 `intervalType`을 안 본다 → 당일(TODAY) 백업에 20일 누적 금액이 섞인다 | `radar.py:793-800` (필터에 interval 없음) | interval을 끝까지 넘기고 필터에 추가 |
| 1-3 | 거래일 판정이 공휴일을 모른다 → 평일 휴장일에 전날 데이터가 휴장일 날짜로 저장 | `radar.py:96-110`. `tasks.py:549`에 이미 `_kr_holiday_dates()`가 있음 | `krcalendar.py`로 모아 둘 다 쓰게 함 |
| 1-4 | **KRX 선물 거래량·미결제약정이 없으면 0.0으로 채워** 40일 이력 위에 upsert. 0.0으로 oiChange를 계산해 "신규 롱/숏"까지 만들어 냄 | `krx.py:272-273` (`or 0.0`), `tasks.py:245-251`, `krx.py:292-349` | 없으면 `None`; `store.put_timeseries`가 None을 **쓰지 않게**(현재는 NULL로 덮음, `store.py:216-228`); 판정은 "판정 불가" |
| 1-5 | pykrx 호출 인자가 틀려 가격 조회가 항상 실패 → 모든 종목 price=0, changePct=0으로 "ok" 저장 | `radar.py:693` 두 날짜 위치 인자 → pykrx 1.2.8은 `by_date(ticker="KOSPI")`로 분기 (`stock_api.py:187-191` 확인) | `get_market_ohlcv(date, market=...)`; 실패 시 `None` |
| 1-6 | 금융위 시가총액이 전부 비면 0.0을 timeseries에 씀 | `fsc.py:121-126`, `tasks.py:602` | 전부 비면 `None`, 점 생략 |
| 1-7 | FRED 응답이 2개 점만 와도 10년치 스냅샷을 덮음(백엔드는 스냅샷을 읽음) | `tasks.py:180-183`, `fred.py:61` | 저장본 대비 기간이 크게 줄면 거부(스냅샷 유지, 실패 사유 기록) |
| 1-8 | `task_fsc_prices`가 하루치를 4개 트랜잭션으로 쓰다 중간에 멈추면 다음 실행이 "최신 상태"로 건너뜀. 빠진 날은 영영 안 받음 | `tasks.py:584-613` | 하루 단위 한 트랜잭션(`store.transaction()`), 빠진 날 순회 |
| 1-9 | `/live/radar`가 topN=5·과거 날짜 결과도 기본 스냅샷 이름에 저장 → 다음 15분 동안 그게 "현재 랭킹" | `main.py:313-321`, 백엔드는 이 경우 스냅샷을 안 읽지만 수집기는 씀 | 기본 조건(오늘·top30)일 때만 저장 |
| 1-10 | SEC 목록에서 `13F-HR/A`(정정)도 분기로 세어 분기 중복·부분 포트폴리오 | `sec13f.py:111`; 파싱 테스트 0개 | 원본만 분기로, 정정은 같은 분기에 병합. 파싱 테스트 추가 |
| 1-11 | 2년물 수익률 카드가 스크래퍼 실패 시 선물 **가격**(ZT=F≈102)을 수익률(%)로 ok 표시 | `macro_cards.py:43-62`, `indicators.py:50` | 대체값 없으면 `fail`로 표시 |

**사용자 결정 필요 ①** — 이미 쌓인 합집합 행(1-1)을 정리할지. 권장: 날짜·조합별로 **가장 늦은
수집 시각의 30행만 남기고** 나머지 삭제(되돌릴 수 없는 데이터 변경이라 승인 뒤에만. 직전 백업
`backups/macrodash-20260930-215609.sql`과 작업 직전 백업을 둠).

## 2. P0 — 보안

| # | 문제 | 등급 | 고칠 방법 |
|---|---|---|---|
| 2-1 | **로그인 잠금이 사실상 전역**. Docker Desktop은 모든 접속을 같은 주소로 보여 주므로, LAN의 누구든 15분마다 틀린 비밀번호 한 번으로 주인의 로그인을 영구히 막을 수 있다. 맞는 비밀번호도 429 (`AuthController.java:57-61`이 비밀번호를 보기 전에 거부). 또 실패 횟수가 성공 전엔 안 줄어 한 번 잠긴 뒤엔 오타 한 번에 15분 | 중간 | 잠금 → **지연**으로: 한도 초과 뒤에도 비밀번호는 검사하되 전역 세마포어로 1초 간격 직렬화(16자 무작위 비밀번호면 하루 8.6만 회 시도로는 의미 없음). 실패 횟수는 15분 지나면 감쇠 |
| 2-2 | 기본값이 0.0.0.0 + 평문 HTTP: 공용 와이파이에선 비밀번호·12시간 쿠키가 노출 | 중간 | **사용자 결정 ②** 아래 |
| 2-3 | `/actuator/*`가 인증 필터 밖 (`WebConfig.java:58`은 `/api/*`만) | 낮음 | 노출 끔(`exposure.include: ""`), 헬스체크는 `/api/health` 그대로 |
| 2-4 | 수집기 토큰이 비어 있으면 DNS 리바인딩으로 `/maintenance/purge` 호출 가능 (Host 검증 없음) | 낮음 | `TrustedHostMiddleware`(localhost·127.0.0.1·collector); 토큰 비면 기동 거부(명시적 허용 변수 없을 때) |
| 2-5 | AI 오류 메시지에 Cloudflare 계정 ID가 든 URL이 화면·로그로 | 낮음 | `describe()`에서 URL 마스킹 |
| 2-6 | `/api/auth/login`이 크기 제한 없는 JSON을 받음 | 낮음 | 4KB 초과 413 |
| 2-7 | 백업 파일이 `-rw-r--r--`(개인 데이터), 무한 누적 | 낮음 | `umask 077`, 최근 N개만 보존 |
| 2-8 | `/live/ticker/{symbol:path}` 심볼 검증 없음(호스트 고정이라 SSRF는 아님) | 낮음 | `^[A-Za-z0-9^=.\-]{1,24}$` |
| 2-9 | 운영 보조: `make test`에 ruff가 없음(CI엔 있음); `pip-audit`/`npm audit`이 어디에도 없음 | 정보 | `test-collector`에 ruff 추가, CI에 audit 단계(실패해도 경고만) |

확인된 것: 인증 필터 경로 매칭(인코딩·`..`·대소문자 우회 불가), JWT(HS256·32바이트 강제·jti 폐기·비밀번호 스탬프),
쿠키(HttpOnly·SameSite=Strict), CORS(명시 origin + credentials), SQL 전부 파라미터, 로그 비밀값 가림,
XSS 경로 없음(`dangerouslySetInnerHTML` 0건), 외부 호출 호스트 전부 상수, XML 외부 엔티티 없음, 모든
requests에 timeout(pykrx 내부만 예외 → 4-5). 의존성에 알려진 심각한 취약점 없음(Spring Boot 4.1.1,
Next 15.5.26, jjwt 0.12.6, requests 2.34.2).

**사용자 결정 필요 ②** — `WEB_BIND_HOST` 기본값. 휴대폰 접속을 쓰시므로 **0.0.0.0 유지**를 권장하되,
(a) 화면이 LAN IP에서도 API를 찾게 고치고(§4-4 — 지금은 휴대폰에서 아예 안 됨), (b) `.env.example`과
문서에 "공용 와이파이에서는 쓰지 말 것"을 명확히 적는다. 127.0.0.1로 닫고 Tailscale 같은 터널을 쓰는
방법은 새 도구를 들이므로 권하지 않음.

## 3. P1 — 틀린 숫자 (백엔드)

| # | 문제 | 근거 | 고칠 방법 |
|---|---|---|---|
| 3-1 | **유동성 "4주/12주 변화"가 4행/12행(≈4일/12일) 변화**. 행은 WALCL·TGA·RRP 날짜 합집합이라 일 단위. 국면 화면은 날짜로 맞게 계산해 두 화면이 다른 값 | `LiquidityService.java:97-109`. 실측: 4주 전 행이 `2026-09-25`(6일 전) | 날짜 기준(`minusWeeks`)으로. `SeriesMath.changeOverWeeks` 하나를 두 서비스가 공유 |
| 3-2 | **13F 분기 비교를 종목 이름으로 키잉**. 수집기는 (이름, CUSIP, 종류)로 저장하므로 A주·C주가 서로 비교돼 가짜 "비중 확대/축소", 사라진 종류는 "전량 매도"로 안 잡힘. consensus/newBuys가 이어받음 | `Sec13FService.java:155-160, 210-215` vs `sec13f.py:229`. `GuruService.weightsOf`는 CUSIP을 씀 | CUSIP(없으면 이름+종류)로 키잉. 비교 로직을 순수 함수(`analytics/HoldingsDiff`)로 빼서 Jackson 없이 테스트 |
| 3-3 | 직전 분기에 없던 종목의 shares가 null이면 "신규 매수" 대신 "비중 확대"; consensus가 없는 value/weight를 0으로 합산 | `Sec13FService.java:189-203, 371-372, 489-490` | 없는 값은 합산·평균에서 제외, 신규는 shares와 무관하게 신규 |
| 3-4 | AI 입력 텍스트의 KRX 투자자 블록이 없는 값을 "당일 0 · 5일 0"으로 | `SnapshotTextService.java:430-432`(`asDouble(0)`); 수집기도 `krx.py:478`에서 0 채움 | 둘 다 null → "데이터 없음" |
| 3-5 | `Map.of(...)`에 null이 들어가면 NPE → 500 (수집기 응답 키 누락, totalValue 없는 분기) | `DataStatusService.java:160, 309`, `Sec13FService.java:119-122` | null 허용 맵 |
| 3-6 | 섹터가 없으면 문자열 `"null"` 저장 → 화면에 "null" | `GuruService.java:300, 377` | `Json.asText`가 null이면 "미분류" |
| 3-7 | `lookbackWeeks` 큰 값에 정수 오버플로 → 검사 통과·이벤트 0건으로 ok; percentile NaN 통과 | `CotService.java:186-194` | 범위 클램프, NaN 거부 |
| 3-8 | `Scorecard.dailyReturns`가 previous≤0일 때 0.0을 넣음 | `Scorecard.java:112` | 표본 제외 |
| 3-9 | 교차 검증 장중 판정이 한국 공휴일을 모름 | `Verification.java:189-204` | `CalendarService` 공휴일 반영 |

## 4. P1 — 멈춤·대기·오동작 (신뢰성)

| # | 문제 | 근거 | 고칠 방법 |
|---|---|---|---|
| 4-1 | **레이더 랭킹이 저장본이 15분 지나면 수집기를 동기 호출**(읽기 타임아웃 90초). 수집기가 응답을 못 하면 `/api/radar/ranking` 90초, consensus 180초, 원본 텍스트·AI 리포트 270초 멈춤. "화면은 수집을 기다리지 않는다" 원칙 위반 | `RadarService.java:120-142`, `CollectorClient.java:40` | 저장본이 있으면 `StoreReader`의 비동기 경로로 수집 요청 후 저장본 반환 |
| 4-2 | `runTask(wait=false)`도 같은 90초 타임아웃의 동기 HTTP. 수집기가 받기만 하고 안 답하면 30초마다 한 요청이 90초 멈춤 | `StoreReader.java:138` | 제어용 RestClient(읽기 3초) 분리: runTask(wait=false)·status·tasks. `/api/status` 계열도 같은 클라이언트 |
| 4-3 | `readSnapshot`이 `DataAccessException`을 삼켜 DB 장애를 "저장본 없음"으로 보고 수집기를 90초씩 기다림 | `StoreRepository.java:51-63` | 전파(핸들러가 500으로 가림). `/api/health`가 DB 연결을 반영(지금은 상수 ok) |
| 4-4 | **화면이 API 주소를 빌드 시점 `localhost:8080`으로 박음** → 휴대폰에서 열면 휴대폰 자신의 localhost를 찾아 실패. 문서의 우회(LAN IP를 박기)는 맥에서 쿠키(SameSite=Strict)가 안 보내져 로그인이 깨짐 | `lib/api.ts:12-13`, `next.config.mjs:24-26` | 실행 시점에 `location.hostname` + 백엔드 포트로 조립(포트만 빌드에 넣음), 전체 URL 덮어쓰기는 유지. `FRONTEND_ORIGIN`에 LAN origin도 허용(백엔드는 이미 쉼표 목록 지원) |
| 4-5 | **세션 만료를 아무 페이지도 처리 안 함** → 12시간 뒤 "수집기를 실행하세요"라는 틀린 안내만 뜨고 로그인으로 안 보냄 | `useApi.ts:90-93`은 `unauthorized`를 세우지만 읽는 곳은 layout뿐 | `useApi`의 401이 `RefreshProvider`의 콜백으로 `/login` 이동 |
| 4-6 | 필터를 바꾸면 응답이 올 때까지 **이전 필터의 데이터가 새 제목 아래** 보임(로딩 표시 없음) | `useApi.ts:78, 153` | `dataPath === path`일 때만 data 반환 |
| 4-7 | 매크로 화면이 저장된 자동 갱신 설정을 읽기 전에 `live=true` 수집을 먼저 쏨 | `AutoRefresh.tsx:47-62`, `macro/page.tsx:66` | 지연 초기화 `useState(() => readStored())` |
| 4-8 | 13F 주간 수집 중 백엔드가 같은 태스크를 요청하면 180초 뒤 "fail"로 기록(실제론 성공) | `tasks.py:948, 998-1002` | "joined" 상태(실패 아님) |
| 4-9 | pykrx 내부 requests에 timeout이 없어 스케줄러 스레드가 무한 대기 가능 | pykrx `webio.py:42-84` | 스레드 + `result(timeout=20)` |
| 4-10 | 수동 새로고침이 무거운 `/api/status`를 2초마다 최대 45번 폴링 | `useRefreshSignal.tsx:50-51` | 가벼운 엔드포인트 폴링 + 백오프 |
| 4-11 | 상태 화면이 모르는 값을 "0 / 0", "0 행"으로; 비중 이력 차트가 없는 값을 0으로(0 = 미보유 의미) | `status/page.tsx:91-100`, `institutions/page.tsx:62` | `?? 0` 제거 → "—"/null |
| 4-12 | 수집기 DB 풀의 죽은 연결, 로그 무제한, 메모리 제한 등 운영 항목 | §7 | |

## 5. P2 — 성능 (측정 뒤 판단)

| # | 문제 | 측정값(변경 전) | 고칠 방법 · 측정 방법 |
|---|---|---|---|
| 5-1 | **timeseries 쓰기 증폭**: 매시간 FRED 12시리즈 ~2,600점 + 유동성 1만 점을 전부 다시 upsert | `pg_stat_user_tables`: 31,263행에 **n_tup_upd 3,460,245** (행당 110회). 백엔드는 이 두 데이터셋의 timeseries를 읽지도 않음 | 시리즈별 `MAX(obs_date)` 이후(+며칠 여유)만 upsert. 측정: 한 시간 뒤 `n_tup_upd` 증가량 |
| 5-2 | 요청마다 수 MB JSONB를 다시 파싱: 13F q8 스냅샷 12개(최대 2.5MB), `equity.price_history` 2.2MB | `guru/profiles` 282ms, `guru/similarity` 272ms, `stock/scorecard` 156ms, `snapshot/text` 341ms (그 외는 5~40ms) | `StoreRepository`에 파싱 결과 캐시(키: name, 검증: `collected_at` 한 줄 조회). 새 의존성 없음. 측정: `bench-api.sh` 전후 |
| 5-3 | fresh 스냅샷을 읽을 때마다 `refresh_requests` 조회 → `/api/snapshot/text`가 ~90회 왕복 | JDBC DEBUG로 횟수 측정 | 요청당 한 번 읽기 |
| 5-4 | 화면: 모든 GET이 `Content-Type: application/json`을 보내 CORS preflight 2배 | 네트워크 탭 | body 있을 때만 |
| 5-5 | 화면: 차트 입력 배열을 매 렌더 새로 만들어 분당 6~7개 차트 애니메이션 재실행 | — | `useMemo` 또는 `isAnimationActive={false}` |
| 5-6 | `equity.price_history` 5MB 스냅샷을 매시간 통째로 다시 씀(dead TOAST ~120MB/일) | `snapshots` 20MB 중 대부분 | **다음 단계로 미룸** — 5-2로 읽기 지연이 해결되면 저장 구조 변경은 근거가 약함. 측정값만 남김 |

## 6. P3 — 구조 (동작 보존, 고정 테스트 먼저)

원칙: 새 인프라·추상화 계층 없음. 아래는 **버그 수정에 필요하거나, 기능 사이 의존을 없애거나,
테스트를 가능하게 하는 것만** 고른다. 나머지 분할 제안(예: `store.py` 7개 파일, `AiService` 3분할)은
기록만 하고 이번엔 하지 않는다.

백엔드
- `support.Json`에 `{dates[],close[]}`·`points[]`→`NavigableMap` 헬퍼(현재 4곳·2곳에 복제). 이로써
  `insight→institution`, `positioning→insight` 의존이 사라짐(둘 다 헬퍼 재사용 때문에만 존재).
- `analytics.SeriesMath.subtractAligned`·`changeOverWeeks`(3-1).
- `analytics.HoldingsDiff`(순수, 3-2·3-3)와 `support.HoldingsJson`; `Institutions` 상수 분리.
- ADR 0001 보강: "스냅샷은 `read.StoreReader`로만"(지금은 feature→store 직접 읽기 5곳, 그중
  `RadarService.options`는 신선도 정책을 우회) + `ArchitectureRulesTest`에 규칙 추가.
- `SnapshotTextService`의 서식 함수를 `TextFormat`(순수)으로 — 화면 `lib/format.ts`와 같은 규칙인지 테스트.

수집기
- `app/numbers.py: to_float(value) -> float|None` 하나로 11개 변형 통일(1-4·1-6의 원인).
- `app/krcalendar.py`(거래일·공휴일, 1-3).
- `radar.py`: 소스별 **fetch와 parse 분리**(Daum·Naver·pykrx), `history.py`(1-1·1-2). 파서 테스트 추가.
- `store.py`: `transaction()`과 `replace_observations`만 추가(분할 안 함).
- `tasks.py`: 실행기(`runner.py`)와 태스크 그룹 분리는 **마지막에, 남는 시간이 있을 때**.

화면
- `useApi` 수정(4-5·4-6), `api.ts` 런타임 base(4-4).
- 페이지 안 순수 함수 → `lib/`(radar 정렬·fundamentals 행, 유동성 B→억 환산, spread 요약, 상관 강도,
  streak 텍스트, 범위 자르기 `sliceByRange`) + 테스트. `components/` 안의 훅 2개 → `hooks/`.
- `lib/format.ts` 숫자 서식 테스트(현재 0개; 경계 반올림 버그 3건 포함).
- `lib/types.ts` 969줄 → 기능별 파일 + 배럴(import 경로 불변).
- `charts.tsx`: 축 계산 순수 함수(`lib/chartAxis.ts`)만 분리, 컴포넌트 분할은 하지 않음.

## 7. 운영·DB

치명·높음 없음. 스키마·인덱스·백업·복원·마이그레이션은 설계대로 동작함을 실제 DB에서 확인했다
(업서트 대상 = PK 일치, 읽기 경로 전부 인덱스 스캔, 복원 덤프에 `schema_migrations` 포함, 마이그레이션
단일 트랜잭션 + advisory lock).

| # | 문제 | 근거 | 고칠 방법 |
|---|---|---|---|
| 7-1 | **PostgreSQL 재시작 뒤 수집기 풀이 죽은 연결을 나눠 줌** → 최대 8개 태스크가 연달아 실패하고, 실패 기록 자체도 유실될 수 있음(`record_task_run`이 DB 오류를 삼킴) | `store.py:46-56` `ConnectionPool`에 `check=` 없음 | `check=ConnectionPool.check_connection` |
| 7-2 | 메모리 제한 없음: JVM이 Docker VM 전체(7.75GiB)의 70%를 힙 상한으로 잡음. 다른 프로젝트 컨테이너 6개와 VM 공유 | `docker inspect` mem=0 | `mem_limit`(backend 1g, collector 768m 등) |
| 7-3 | 영구 실패 티커(MMC → MRSH로 이름 바뀜)가 `/status`에 안 보이고 매시간 ERROR 2줄 | `equities.py:152`; 태스크는 "136/137"로 ok | 매핑 수정, 실패 심볼을 detail에 적기, yfinance 로거 WARNING |
| 7-4 | 컨테이너 로그가 무제한(json-file, 옵션 없음), 수집기 로그의 2/3가 헬스체크 접근 로그 | 15분에 `/health` 45줄 | `max-size 10m · max-file 3`, uvicorn `--no-access-log` |
| 7-5 | 단일 태스크 실행에서 `empty`를 run 상태 `fail`로 기록 → `make status`가 "실패"라고 함 | `tasks.py:1096-1103` | run 상태를 태스크 상태에서 유도(empty는 fail 아님) |
| 7-6 | postgres 헬스체크가 소켓만 봐서 최초 initdb 중에도 healthy | `docker-compose.yml:45` `-h` 없음 | `pg_isready -h localhost` |
| 7-7 | 백업 덤프가 pg_dump 16.10+ 형식(`\restrict`) — 오래된 psql로는 복원 실패(안전하게 실패) | CI는 runner의 psql을 씀 | CI에서 postgres 이미지의 psql 사용 |
| 7-8 | `make test`와 CI 불일치(ruff·이미지 빌드), `Makefile db:`가 `macro/macrodash` 하드코딩, `test-collector`가 `macro:macro` 하드코딩 | `Makefile:130, 157, 181` | `test-collector`에 ruff, `.env` 값 사용 |
| 7-9 | 화면 컨테이너 헬스체크 없음, `npm run start`가 PID 1 | `frontend/Dockerfile:44` | `node … next start` + healthcheck |
| 7-10 | 백엔드가 스키마 버전을 안 봄 — 첫 "컬럼 추가" 마이그레이션 때 레이스 | 지금 V2는 인덱스만 | `depends_on: collector: service_healthy` 또는 버전 게이트. 이번엔 **문서화만** |

성능 측정에서 7-11: `timeseries` 업서트가 값이 같아도 새 버전을 씀(`updated_at = EXCLUDED.updated_at`) —
§5-1과 함께 `WHERE value IS DISTINCT FROM EXCLUDED.value`.

## 8. 진행 방식

1. 브랜치 `claude/review-2026-10`에서 **주제 하나 = 커밋 하나**. 리팩터링 커밋과 버그 수정 커밋을 섞지 않음.
2. 순서: §1 → §2 → §3 → §4(+§7) → §5 → §6. 각 버그는 **실패하는 테스트를 먼저** 넣고 고친다. 테스트 없는
   코드를 옮길 땐 현재 동작을 고정하는 테스트부터. 예상 커밋 수 40~50개.
3. 커밋마다 `make test`(수집기 pytest+ruff, 백엔드 `mvn verify`, 화면 check+vitest+lint+build) 통과.
4. 단계(§)마다 `main`에 fast-forward 병합·GitHub push·`make up`으로 실제 스택에 반영하고 `make doctor`로 확인.
   한 번에 큰 병합을 하지 않는다.
5. 성능 항목은 `scripts/bench-api.sh`와 `pg_stat_user_tables` 전후 수치로 판단. 수치가 안 좋아지면 되돌림.
6. 끝나면 별도 에이전트에게 전체 diff 리뷰를 맡기고, 정확성·요구사항에 영향 있는 것만 고친다.
7. 중요한 설계 결정은 ADR로: 0005(수급 이력 저장 단위와 교체 쓰기), 0006(화면 API 주소 런타임 결정),
   ADR 0001 개정(스냅샷 읽기 경로).

멈추고 물어볼 때: 기존 데이터 삭제·변경(결정 ①), 공개 API·URL 하위 호환 깨짐, 새 유료 서비스·인프라,
`.env`에 직접 넣어야 할 값, 버그인지 의도인지 코드로 판단 안 될 때.

## 9. 사용자 결정 요약

| 결정 | 권장 |
|---|---|
| ① 이미 쌓인 레이더 이력 합집합 행 정리(삭제) | 날짜·조합별 최신 수집분 30행만 남김. 백업 뒤 실행 |
| ② `WEB_BIND_HOST` 기본값 | 0.0.0.0 유지 + LAN 접속 수정(4-4) + 문서 경고 |
| ③ P3 범위 | 위 §6 목록대로(버그·의존·테스트 관련만). 큰 파일 분할은 미룸 |
| ④ `equity.price_history` 저장 구조 변경 | 미룸(5-2 결과 보고 결정) |

## 10. 변경 전 측정값 (2026-10-02)

```
# scripts/bench-api.sh 5  (중앙값 ms / bytes)
/api/macro/overview                 38 / 29435
/api/macro/fx (4종·5y)              19 / 45056
/api/guru/profiles                 282 /  2173
/api/guru/similarity               272 /  3238
/api/stock/scorecard?symbol=AAPL   156 /  1543
/api/snapshot/text                 341 /  5470
/api/analytics/regime?years=5       23 /  2663
그 외 화면 API                     3~34
# pg_stat_user_tables
timeseries   31,263행  n_tup_upd 3,460,245
observations 20,711행  n_tup_upd    86,755
snapshots        67행  n_tup_upd     8,435  (총 20MB; 13F q8 최대 2.5MB, equity.price_history 2.2MB)
# collector_task_runs p50 (7일): toss_radar_universe 39.7s, sec_13f 37.1s, equity_history 23.8s, fred_series 7.9s
```
