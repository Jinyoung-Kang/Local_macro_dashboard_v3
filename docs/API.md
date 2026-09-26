# REST API

두 개의 서비스가 API를 노출합니다.

- **백엔드** `http://localhost:8080` — 화면이 쓰는 API. 인증 필요.
- **수집기** `http://localhost:8000` — 내부용. 백엔드만 호출합니다.

## 1. 백엔드 (`/api`)

### 인증

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/auth/login` | `{"password": "..."}` → 세션 쿠키(HttpOnly·SameSite=Strict). 틀리면 401, 연속 5회 실패 후 **429 + `Retry-After`**(30초부터 2배씩, 최대 15분) |
| GET | `/api/auth/session` | `{"authenticated": bool}` |
| POST | `/api/auth/logout` | 쿠키 만료 |

로그인·세션·헬스체크를 제외한 모든 `/api/**`는 유효한 세션 쿠키를 요구합니다
(없으면 401).

### 모든 응답에 공통인 것

읽기 엔드포인트는 **같은 모양의 봉투**를 씁니다. 화면이 한 가지 방식으로만
처리하면 되도록 맞춰 둔 것입니다.

| 필드 | 뜻 |
|---|---|
| `available` | 보여 줄 값이 있는가. `false`면 `message`에 이유가 있습니다 |
| `message` | 왜 값이 없는지 (사람이 읽는 문장) |
| `collectedAtKst` | 이 값을 **언제 수집했는지** (`"2026-09-17 04:08:11 KST"`) |
| `ageSeconds` | 수집 후 경과 초 |
| `stale` | 신선도 기준을 넘겼는가. 화면이 경고색으로 바꿉니다 |

숫자 필드는 **값이 없으면 `null`입니다.** 0으로 채우지 않습니다 — 화면에서
"0.00%"는 '데이터 없음'이 아니라 '보합'으로 읽히기 때문입니다
([PRINCIPLES.md](PRINCIPLES.md)).

추정치·대용값에는 플래그가 함께 옵니다.

| 플래그 | 뜻 |
|---|---|
| `isEstimated` | 공식 확정치가 아닌 추정치 (예: KRX 키 없을 때의 선물) |
| `isProxy` | 실제 지표가 아닌 **대용값** (예: MOVE) |
| `coverage` | 일부만 계산에 반영됐을 때 덮은 비중 (13F 위험 분석) |
| `missing` | 이 응답에 **없는** 항목 (종목 스코어카드의 재무·성장) |
| `isHistorical` · `historyDate` · `warning` | 수급 랭킹이 누적 이력으로 대체됐을 때 |

**저장본이 없어도 HTTP 200입니다.** `{"available": false, "message": "..."}`를
돌려줍니다 — 화면이 500 에러 페이지 대신 "수집기를 먼저 실행하세요" 같은 안내를
띄울 수 있어야 하기 때문입니다.

### 📊 매크로

| 경로 | 설명 |
|---|---|
| `GET /api/macro/overview?live=` | 카드 + 스프레드 + 신선도. `live=true`면 저장본을 다시 받을 기준이 15분 → **60초**로 내려갑니다(화면의 자동 갱신이 1분 이하일 때). 60초보다 낮추지 않는 이유는 [PRINCIPLES.md](PRINCIPLES.md) |
| `GET /api/macro/risk` | VIX·MOVE·HY OAS·CP 스프레드·STLFSI4 |
| `GET /api/macro/advanced` | 심화 지표 6종 (해석·백분위 포함) |
| `GET /api/macro/usdkrw` | 원/달러 환율 (달러 금액의 원화 병기용, 매크로 카드와 같은 값) |
| `GET /api/macro/fx?ids=usdkrw,dxy&period=1y&mode=index` | 환율·달러인덱스 비교 (여러 계열 겹쳐 보기). `mode=index`는 기준일 100, `raw`는 원래 단위 |
| `GET /api/macro/fx/options` | 고를 수 있는 기간·기본 선택 |
| `GET /api/macro/scraped` | 비공식 참고 시세 |
| `GET /api/macro/spread?longId=DGS10&shortId=DGS2` | 공식 일별 스프레드 시계열 |
| `GET /api/macro/fred/{seriesId}?years=10` | FRED 시리즈 원본 |
| `GET /api/macro/ticker?symbol=^VIX&period=1y` | 개별 지표 차트 |

### 나머지 메뉴

| 경로 | 설명 |
|---|---|
| `GET /api/liquidity?years=3` | 연준 순유동성 |
| `GET /api/sector/rotation?period=1M` | 섹터·자산군 수익률·순위 |
| `GET /api/sector/momentum` | 1주/1개월/3개월 모멘텀 순위 |
| `GET /api/sec13f/institutions` | 기관 목록 |
| `GET /api/sec13f/portfolio?cik=&quarters=8&topN=30` | 기관 포트폴리오 + 분기 대비 액션 |
| `GET /api/sec13f/consensus?ciks=&reportDate=&minHolders=2` | 교집합 |
| `GET /api/sec13f/new-buys?ciks=&reportDate=&minHolders=3` | 이번 분기 공통 신규 매수 |
| `GET /api/guru/profiles` | 기관별 성격 (집중도·유효 종목 수·회전율) |
| `GET /api/guru/similarity` | 기관 간 유사도 행렬 (겹침 비중 + 코사인) |
| `GET /api/guru/holders?q=NVIDIA` | 이 종목을 누가 들고 있나 |
| `GET /api/guru/risk?cik=&benchmark=SPY&years=1` | 기관 포트폴리오 위험 (🧬 기관 13F 스타일·위험 화면. API 경로는 호환을 위해 `guru` 유지). 응답의 `coverage`가 **덮은 비중**을 항상 담습니다 |
| `GET /api/stock/universe` | 스코어카드를 낼 수 있는 종목 목록 |
| `GET /api/stock/scorecard?symbol=AAPL&benchmark=SPY&years=1` | 종목 스코어카드 (가격 기반 지표만 — `missing`이 빠진 항목을 알려 줍니다) |
| `GET /api/cot/assets`, `/api/cot/overview`, `/api/cot/asset?name=` | CFTC COT |
| `GET /api/cot/extremes?name=&percentile=95&lookbackWeeks=52` | 극단 포지션 이후 4·13주 수익률 (가격은 ETF 대용) |
| `GET /api/krx/futures?days=60` | KOSPI200 선물 시계열 + 최신 판정 |
| `GET /api/krx/oi-trend` | 미결제약정 증감 (최신·직전·변화) |
| `GET /api/krx/investor-trend` | Daum 투자주체별 수급 (계약수) |
| `GET /api/krx/intraday?minutes=30` | 장중 수급 가속도 |
| `GET /api/radar/options` | 선택지 목록 |
| `GET /api/radar/ranking?market=&investor=&tradeType=&topN=&intervalType=` | 수급 랭킹 |
| `GET /api/radar/consensus?market=&tradeType=&topN=&intervalType=` | 외국인·기관이 **같은 방향**으로 움직인 종목 (두 상위 N 목록의 교집합) |
| `GET /api/radar/history?market=&investor=&tradeType=` | 누적 이력 |
| `GET /api/radar/diagnostics` | 5개 소스 연결 진단 |

### 🇰🇷 국내 공공 API 데이터

키가 없거나 수집 전이면 `available=false`와 무엇을 해야 하는지(`message`)를 돌려줍니다.

| 경로 | 설명 |
|---|---|
| `GET /api/calendar/kr-holidays` | 한국 공휴일(천문연). 화면 시계가 KRX 휴장 판정에 보탭니다 |
| `GET /api/kr/fundamentals?codes=005930,000660` | 종목별 부채비율·매출/영업이익 증가율(적자 구간은 `operatingTurn`)·영업이익률·ROE + 공식 종가·시가총액·PER·PBR. 6자리 코드만, 최대 60개. `priceDate`가 시가총액의 기준일 |
| `GET /api/kr/market-totals?days=180` | 시장별 시가총액·거래대금 합계 (원, 20~400일) |

### 🗄️ 상태 · 검증

| 경로 | 설명 |
|---|---|
| `GET /api/status` | 수집 현황·신선도·누락 데이터셋 |
| `GET /api/status/tasks` | 수집 작업 목록 |
| `GET /api/status/history?task=&limit=40` | 태스크 실행 이력 |
| `POST /api/status/refresh?runFast=true` | 수동 새로고침 |
| `POST /api/status/run/{taskName}` | 특정 태스크 실행 |
| `POST /api/verification` | 교차 검증 (판정 포함) |
| `GET /api/status/issues` | 수집 오류·경고 모음 — 지금 실패 중인 태스크, 최근 24시간 실패 이력(같은 사유는 묶음·횟수), 누락 데이터셋. `text`는 복사용이며 **비밀값이 가려져** 있습니다. 실행 기록은 DB에서 직접 읽어 수집기가 꺼져 있어도 동작 |
| `GET /api/status/public-apis` | 국내 공공 API 연결 진단 — **API마다 실제 1회 호출**. 결과에 키 값은 없음 |

### 🧭 국면 · 🔗 상관관계

| 경로 | 설명 |
|---|---|
| `GET /api/analytics/series` | 상관 분석에 쓸 수 있는 계열 목록 (20종) |
| `GET /api/analytics/correlation?x=&y=&window=60&years=3&mode=change` | 롤링 상관계수 + 산점도. `mode=level`에는 허위 상관 경고가 붙습니다 |
| `GET /api/analytics/regime?years=5` | 4국면 판정 + 근거 신호 + 주간 이력·구간 |

### 🏦 투자자별 매매 (토스증권 공식)

| 경로 | 설명 |
|---|---|
| `GET /api/kr/investor-flows` | 코스피·코스닥 전체 투자자별 매매대금(원) — 최근일·5일·20일 누적, 연속 일수, 기관 7세부, 20일 계열 |
| `GET /api/kr/stock-flows?codes=005930,000660` | 종목별 투자자 매매동향(주, 20거래일) — 누적·연속 일수·연기금·외국인 보유율 변화. 최대 60개, 형식이 틀린 코드는 무시 |
| `GET /api/krx/spot-futures` | 코스피 현물(토스, 원) × KOSPI200 선물(Daum, 계약) 투자자별 **방향** 비교. 기준일이 다르면 당일 판정 안 함 |

누적 값은 `{sum, days, window}`입니다. `days < window`면 값이 없는 날(당일 잠정치의 null)을 건너뛴 것입니다.

### 📋 전체 원본 데이터

| 경로 | 설명 |
|---|---|
| `GET /api/snapshot/text` | 수집한 전체 대시보드 원본 텍스트 + 생성 시각·분량 (AI 분석 없음). 매크로 화면 상단의 "원본 데이터 보기/복사"가 씁니다 |

### 🤖 AI · 🔌 토스

| 경로 | 설명 |
|---|---|
| `GET /api/ai/engines` | 엔진 목록 + 키 보유 여부 + 예상 속도(`speedHint`)·대기 한도 |
| `GET /api/ai/report-types` | 리포트 종류 |
| `GET /api/ai/snapshot-text` | AI에 전달되는 원본 텍스트 (본문은 `/api/snapshot/text`와 같습니다) |
| `POST /api/ai/report` | `{engineId, reportType, extraInstruction}` |
| `POST /api/ai/test?engineId=&prompt=` | 연결 테스트 |
| `GET /api/ai/toss/diagnostics` | 토스 연결 진단 |
| `GET /api/ai/toss/exchange-rate?base=USD&quote=KRW` | 환율 조회 |
| `GET /api/ai/toss/indices?symbols=KOSPI,KOSDAQ` | 지수 조회 |

## 2. 수집기 (내부용)

`COLLECTOR_API_TOKEN`을 설정하면 아래 표에서 🔒 표시된 경로에 `X-Service-Token`
헤더가 필요합니다. "쓰기"만이 아니라 **외부 수집을 일으키거나 시장 데이터를
돌려주는 경로 전부**입니다 — 조회처럼 보이는 `/live`·`/verify`·`/toss`도 호출
한 번이 외부 API 호출과 저장을 부릅니다.

`/health`와 `/status`는 토큰 없이 열어 둡니다. 컨테이너 헬스체크와
`make status`·`scripts/doctor.sh`가 헤더 없이 부르고, 비밀값을 담지 않기
때문입니다(키는 설정 여부만 `true/false`로 알립니다).

토큰을 설정하지 않으면(로컬 기본값) 아무것도 막지 않습니다. 대신 수집기 포트는
`127.0.0.1`에만 열립니다([PRINCIPLES.md](PRINCIPLES.md)).

| 경로 | 설명 |
|---|---|
| `GET /health` | 헬스체크 |
| `GET /status` | 저장 현황 + 키 보유 + 수집 주기 |
| `GET /tasks` | 태스크 목록 |
| `GET /task-history?task=&limit=` | 실행 이력 |
| 🔒 `POST /collect?group=fast\|slow\|weekly\|all&wait=true` | 작업군 실행 |
| 🔒 `POST /collect/task/{taskName}` | 태스크 1건 실행 |
| 🔒 `POST /refresh?scope=global` | 새로고침 기준 시각 갱신 |
| 🔒 `POST /maintenance/purge?days=400` | 오래된 누적 이력 정리 |
| 🔒 `GET /live/radar?...` | 수급 랭킹 즉시 수집 (폴백 체인 전체) |
| 🔒 `GET /live/ticker/{symbol}?period=` | 티커 시계열 즉시 수집 |
| 🔒 `GET /live/daum-intraday?minutes=30` | 장중 수급 가속도 |
| 🔒 `GET /live/radar-history`, `/live/radar-history-dates` | 누적 이력 |
| 🔒 `GET /verify/readings?market=&investor=&tradeType=` | 검증용 원자료 (판정은 백엔드) |
| 🔒 `GET /diagnostics/connections` | KIS·LS·Daum·Naver·PyKrx 진단 |
| 🔒 `GET /diagnostics/public-apis` | 특일정보·주식시세·DART 진단 (API당 1회) |
| 🔒 `GET /diagnostics/toss`, `/toss/*` | 토스 진단·조회 |
| `GET /catalog` | 지표·기관·ETF·COT 정의 |

FastAPI 자동 문서: <http://localhost:8000/docs>
