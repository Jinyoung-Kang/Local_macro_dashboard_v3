# REST API

두 개의 서비스가 API를 노출합니다.

- **백엔드** `http://localhost:8080` — 화면이 쓰는 API. 인증 필요.
- **수집기** `http://localhost:8000` — 내부용. 백엔드만 호출합니다.

## 1. 백엔드 (`/api`)

### 인증

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/auth/login` | `{"email": "...", "password": "..."}` → 세션 쿠키(HttpOnly·SameSite=Strict, DB 세션 — ADR 0010)와 `{ok, user:{email, role, tier, demo:false}}`. 없는 이메일과 틀린 비밀번호는 같은 401. (접속 주소, 이메일)마다 연속 5회 실패 뒤에는 느리게(전역 1초에 1건) 처리하며 틀리면 **429 + `Retry-After`**(안내값 30초부터 2배씩, 최대 15분) — 맞는 비밀번호는 그래도 통과. 다른 느린 시도가 진행 중이면 즉시 429(1초). 본문은 4KB까지(초과 413). 다른 출처(Origin)에서 온 POST는 403 |
| GET | `/api/auth/session` | `{"authenticated": bool, "mailAvailable": bool, "readMode": …, "user": {email, role(OWNER·MEMBER), tier(FREE·PRO), demo(bool)}}`. `mailAvailable`은 가입·재설정을 쓸 수 있는지(메일 발송 설정). `readMode`·`user`는 로그인한 경우에만 옵니다. 데모 열람(ADR 0013)이면 세션 없이도 `authenticated: true`, `user`는 `{email: null, role: "MEMBER", tier: "PRO", demo: true}`입니다. 늘 `noLogin`(로컬 무로그인이 켜져 있는가, ADR 0016)과 `ownerMissing`(켜져 있고 이 맥 요청인데 운영자 계정이 없음)이 오고, `user.local`은 쿠키 없이 첫 운영자로 연 경우 true입니다. 로컬 무로그인 운영자의 쓰기는 이 맥 화면의 `Origin`(`http://localhost:<화면 포트>` 등)이 있어야 하고, 없거나 다른 출처·다른 사이트(`Sec-Fetch-Site: cross-site`)면 403 `bad_origin`입니다. 화면은 이 값으로 메뉴를 고르고, 막는 것은 백엔드의 경로별 접근 정책입니다 |
| POST | `/api/auth/logout` | 쿠키 만료 + 그 세션을 DB에서 끊음(재시작 후에도 유지) |
| POST | `/api/auth/logout-all` | 다른 기기의 내 세션을 모두 끊음(지금 기기는 남김) → `{ok, revoked}` |
| GET | `/api/auth/sessions` | 내 로그인 세션 목록 `{sessions:[{id, current, createdAt, lastSeenAt, expiresAt, userAgent}]}` |
| POST | `/api/auth/password` | `{"current", "next"}` — 지금 비밀번호 확인, 규칙(10자 이상·흔한 값·이메일 포함·반복 금지) 위반이면 400, 성공하면 다른 기기 세션을 끊음 |
| POST | `/api/auth/signup` | 공개. `{"email","password","agreed":true}` — 무료 회원, 메일 인증 전. 이미 있는 주소도 **같은 200**(그 주소 주인에게만 안내 메일). 동의·비밀번호 규칙·형식 위반 400, 메일 미설정 503, 접속 주소마다·(주소·이메일)마다 시간당 5회 초과 429(요청 기록이 1만 키로 꽉 찬 동안은 처음 보는 주소도 429, QA-073) |
| POST | `/api/auth/verify` | 공개. `{"token"}` — 메일 인증 링크(24시간, 한 번만). 인증 전 계정은 로그인 시 403 `unverified` |
| POST | `/api/auth/reset/request` | 공개. `{"email"}` — 가입 여부와 무관하게 같은 200. 인증된 계정에만 재설정 메일(30분, 한 번만). 요청 제한(429)은 가입과 같음 |
| POST | `/api/auth/reset/confirm` | 공개. `{"token","password"}` — 새 비밀번호, 성공하면 그 계정의 모든 세션을 끊음 |
| POST | `/api/auth/delete` | `{"password"}` — 비밀번호 재확인 뒤 계정·세션·토큰 삭제(감사 기록은 사용자 번호만 비움), 운영자 계정은 400 |
| GET | `/api/admin/users` | 운영자. 사용자 목록 `{users:[{id, email, role, tier, verified, createdAt}]}` |
| POST | `/api/admin/users` | 운영자. 회원 만들기 `{"email","password","tier"}`(인증된 상태, 처음 비밀번호는 운영자가 전달) |
| POST | `/api/admin/users/{id}` | 운영자. 역할·등급 지정 `{"role":"OWNER"\|"MEMBER","tier":"FREE"\|"PRO"}` — 그 사용자의 다음 요청부터 반영. 자기 운영자 역할은 못 내림. 결과가 무료(MEMBER·FREE)면 전에도 무료였어도 국면 전환 밖의 켜진 알림 규칙을 끔(감사 기록 `disabledAlertRules`에 이번에 끈 개수, 다시 올려도 저절로 켜지지 않음). 정기 평가는 켜진 규칙이라도 지금 유료(운영자·PRO)가 아닌 회원에게는 국면 전환만 배달 |
| GET | `/api/admin/audit?limit=100` | 운영자. 감사 기록(로그인·비밀번호·역할/등급 변경·삭제). 로그인 실패 `login.failure`는 표본만(QA-125) — 메일 인증을 마친 계정은 `detail.failures`(하루 조용해야 끝나는 연속 실패의 1·5·8·16…번째와 한 시간에 한 번, 누적 수), 없는 계정은 `detail.email`, 메일 인증 전 계정은 사용자 번호와 `detail.unverified: true`(둘을 합쳐 한 시간 30건까지). 넘친 개수는 `login.failure.suppressed` `{count, from, to, limit}`(사용자 번호 없음). 성공 `login.success`에는 직전 실패 수 `priorFailures`(있을 때만) |
| GET | `/api/alerts/rules` | 내 알림 규칙 `{available, limit, rules:[{id, kind, params, enabled, createdAt, description}]}`. 상한: 무료 1개(국면 전환만)·유료 20·운영자 50 |
| POST | `/api/alerts/rules` | 만들기 `{"kind":"REGIME_CHANGE"\|"FOREIGN_STREAK"\|"INSTITUTION_NEW","params":{…}}` — 연속 순매수 `{target:KOSPI\|KOSDAQ\|STOCK, code(6자리), n:3~20}`, 13F `{cik}`(추적 12곳). 무료가 다른 종류면 403, 관심 종목은 사용자당 20·전체 60종목(수집 범위 초과면 400). 만든 날 이후 사건만 들어옴 |
| POST | `/api/alerts/rules/{id}` | 켜기·끄기 `{"enabled": bool}`. 켤 때도 만들 때와 같은 검사 — 무료가 국면 전환 밖의 규칙을 켜면 403, 관심 종목 상한 초과면 400 |
| DELETE | `/api/alerts/rules/{id}` | 지우기 |
| GET | `/api/alerts/inbox?limit=50` | 알림함(최근 순) `{items:[{id, kind, occurredOn, title, body, late, createdAt, read}], unread}` — 90일 보관, `late`는 발생 3일 뒤 들어간 '지난 사건' |
| GET | `/api/alerts/unread-count` | 읽지 않은 수(지난 사건 제외) `{unread}` — 사이드바·탭 제목 표시용 |
| POST | `/api/alerts/inbox/read` | 읽음 `{"ids":[…]}` 또는 `{"all":true}` |

`GET /api/health`는 DB 연결을 확인해 `{"status":"ok","database":"ok"}`(200) 또는
`{"status":"degraded","database":"unreachable"}`(503)로 답합니다 — 컨테이너 헬스체크·`make doctor`가 봅니다.

로그인·세션·헬스체크를 제외한 모든 `/api/**`는 유효한 세션 쿠키를 요구합니다
(없으면 401). 경로마다 필요한 역할·등급은 접근 정책 표(`AccessPolicy`, ADR 0010)가 정하며, 모자라면
403 `{"error":"forbidden","required":"OWNER"|"PRO"}`입니다. 운영 경로(수집 실행·새로고침·`/api/status*`·
공공 API 진단·레이더 진단·토스 진단·AI 연결 시험·교차 검증)는 운영자만 부릅니다.

**데모 열람**(ADR 0013, AWS 공개 데모에서만 — 기본은 꺼짐): 백엔드에 `DASHBOARD_DEMO_VIEWER_TOKEN`이 있고 요청 헤더
`X-Demo-Viewer`가 그 값과 같으면, 세션 쿠키 없이 일반 회원·유료 등급의 데모 사용자로 GET을 받습니다(운영 경로는 그대로 403).
GET·OPTIONS 밖(쓰기·HEAD)은 경로와 상관없이 403 `{"error":"demo_read_only","message":"데모에서는 조회만 가능합니다."}`입니다.
예외는 AI 리포트 생성(POST `/api/ai/report`) 하나로, 방문자 전체가 하루 한도를 함께 씁니다(ADR 0014, 아래 🤖 AI 절).
유효한 세션 쿠키가 있으면 세션이 먼저이고, 기능이 꺼져 있거나 값이 틀리면 헤더는 없는 것과 같습니다(401).

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

### 오류 응답

4xx·5xx는 모두 같은 모양입니다.

```json
{"error": "bad_request", "message": "obsDate 날짜 형식이 올바르지 않습니다: abc (예: 2026-09-18)"}
```

| 필드 | 뜻 |
|---|---|
| `error` | HTTP 상태 이름 소문자 (`bad_request`·`unauthorized`·`not_found`·`method_not_allowed`·`bad_gateway`·`internal_server_error` 등) |
| `message` | 사람이 읽는 한국어 설명. 예외 원문·SQL·자바 타입은 담지 않습니다 |

| 상태 | 언제 |
|---|---|
| 400 | 형식이 틀린 파라미터(`obsDate=abc`), 추적하지 않는 식별자(13F `cik`, FRED `seriesId`) — 없는 이름으로 수집을 기다리지 않고 바로 답합니다 |
| 401 | 세션 쿠키가 없거나 만료됨 |
| 403 | 역할·등급이 모자람(`forbidden`, `required`), 다른 출처의 쓰기(`bad_origin`), 데모 열람의 쓰기(`demo_read_only`) |
| 404 · 405 | 없는 경로 · 허용되지 않는 메서드 |
| 429 | 요청이 몰림 — 응답 캐시를 거치는 비싼 계산 11개 경로(전체 요약 텍스트, 기관 프로필·유사도·위험, 보유 기관 검색(검색어가 있을 때 — 보유 목록, QA-133), 13F 포트폴리오·교집합·신규 편입, 국면·판정 성과, 스코어카드)의 동시 실행 상한(QA-131), 비밀번호 해시 상한(QA-019). 표준 모양 + `Retry-After`(초). 잠시 뒤 다시 부르면 됩니다 |
| 502 | 수집기에 닿지 못함 (태스크 수동 실행) |
| 500 | 예상하지 못한 오류. 원인은 백엔드 로그에만 남습니다 |

범위만 벗어난 **숫자**는 오류 대신 허용 범위로 맞춥니다 — `limit`은 1~200, `years`는
최대 100년(저장본보다 길면 결과가 같기 때문). 상관 분석만 `window` 5~250·`years` 1~10(화면 선택지의 최대)으로 접어,
저장본이 10년보다 길어도 최근 10년까지만 계산합니다(QA-133). 로그인 제한(429)만 별도 모양
`{"ok": false, "retryAfterSeconds": n, "message": "..."}`에 `Retry-After` 헤더를 씁니다.

### 📊 매크로

| 경로 | 설명 |
|---|---|
| `GET /api/macro/overview?live=` | 카드 + 스프레드 + 신선도. 스프레드 `points`는 1년보다 오래된 구간을 주 단위(그 주의 마지막 관측)로 솎음(latest·previous는 전체 계열). `live=true`면 저장본을 다시 받을 기준이 15분 → **60초**로 내려갑니다(화면의 자동 갱신이 1분 이하일 때). 60초보다 낮추지 않는 이유는 [PRINCIPLES.md](PRINCIPLES.md) |
| `GET /api/macro/risk` | VIX·MOVE·HY OAS·CP 스프레드·STLFSI4 |
| `GET /api/macro/advanced` | 심화 지표 6종 (해석·백분위 포함) |
| `GET /api/macro/usdkrw` | 원/달러 환율 (달러 금액의 원화 병기용, 매크로 카드와 같은 값) |
| `GET /api/macro/fx?ids=usdkrw,dxy&period=1y&mode=index` | 환율·달러인덱스 비교 (여러 계열 겹쳐 보기). `mode=index`는 기준일 100, `raw`는 원래 단위. `points`는 1년보다 오래된 구간을 주 단위로 솎음(latest·changePct는 전체 계열) |
| `GET /api/macro/fx/options` | 고를 수 있는 기간·기본 선택 |
| `GET /api/macro/scraped` | 비공식 참고 시세 |
| `GET /api/macro/spread?longId=DGS10&shortId=DGS2` | 공식 일별 스프레드 시계열 |
| `GET /api/macro/fred/{seriesId}?years=10` | FRED 시리즈 원본 |
| `GET /api/macro/ticker?symbol=^VIX&period=1y` | 개별 지표 차트 |

### 나머지 메뉴

| 경로 | 설명 |
|---|---|
| `GET /api/liquidity?years=3` | 연준 순유동성. `rows`는 **1년보다 오래된 구간을 주 단위(그 주의 마지막 행)로 솎음** — 최신값·4주/12주 변화는 전체 행으로 계산 |
| `GET /api/sector/rotation?period=1M` | 섹터·자산군 수익률·순위 |
| `GET /api/sector/momentum` | 1주/1개월/3개월 모멘텀 순위 |
| `GET /api/sec13f/institutions` | 기관 목록 |
| `GET /api/sec13f/portfolio?cik=&quarters=8&topN=30` | 기관 포트폴리오 + 분기 대비 액션 |
| `GET /api/sec13f/consensus?ciks=&reportDate=&minHolders=2` | 교집합 |
| `GET /api/sec13f/new-buys?ciks=&reportDate=&minHolders=3` | 이번 분기 공통 신규 매수 |
| `GET /api/guru/profiles` | 기관별 성격 (집중도·유효 종목 수·회전율) |
| `GET /api/guru/similarity` | 기관 간 유사도 행렬 (겹침 비중 + 코사인) |
| `GET /api/guru/holders?q=NVIDIA` | 이 종목을 누가 들고 있나. `rows`는 비중 내림차순 **상위 200행까지**이고 `totalMatches`(자르기 전 일치 건수)·`truncated`(잘렸는지)를 함께 줍니다 — 빈 `q`면 두 필드 없이 안내 문구만. 검색어와 상관없는 보유 목록은 응답 캐시라 몰리면 429(QA-133) |
| `GET /api/guru/risk?cik=&benchmark=SPY&years=1` | 기관 포트폴리오 위험 (🧬 기관 13F 스타일·위험 화면. API 경로는 호환을 위해 `guru` 유지). 응답의 `coverage`가 **덮은 비중**을 항상 담습니다 |
| `GET /api/stock/universe` | 스코어카드를 낼 수 있는 종목 목록 |
| `GET /api/stock/scorecard?symbol=AAPL&benchmark=SPY&years=1` | 종목 스코어카드 (가격 기반 지표만 — `missing`이 빠진 항목을 알려 줍니다) |
| `GET /api/cot/assets`, `/api/cot/overview`, `/api/cot/asset?name=` | CFTC COT |
| `GET /api/cot/extremes?name=&percentile=95&lookbackWeeks=52` | 극단 포지션 이후 4·13주 수익률 (가격은 ETF 대용) |
| `GET /api/krx/futures?days=60` | KOSPI200 선물 시계열 + 최신 판정 |
| `GET /api/krx/oi-trend` | 미결제약정 증감 (최신·직전·변화) |
| `GET /api/krx/investor-trend` | Daum 투자주체별 수급 (계약수) |
| `GET /api/krx/intraday?minutes=30` | 장중 수급 가속도 |
| `GET /api/radar/options` | 선택지 목록 + `supportedInvestors`(토스 폴백 저장본이 있으면 여섯, 없으면 외국인·기관) + `fallbackChain`(수집기 체인 순서) |
| `GET /api/radar/ranking?market=&investor=&tradeType=&topN=&intervalType=` | 수급 랭킹 |
| `GET /api/radar/consensus?market=&tradeType=&topN=&intervalType=` | 외국인·기관이 **같은 방향**으로 움직인 종목 (두 상위 N 목록의 교집합) |
| `GET /api/radar/history?market=&investor=&tradeType=&latest=true&obsDate=` | 누적 이력. `latest=true`면 **한 거래일만** 줍니다 — `dates`(이 조건의 기록이 있는 날, 최신 순), `obsDate`(고른 날, 비우면 가장 최근), `rows`(그날 기록). 빼면 전 기간(`startDate`로 시작일 지정)이되 **최신 5,000행까지**(최신 날짜 순)만 주고 `rowLimit`(5000)·`truncated`(true면 더 오래된 행이 남음)를 함께 줍니다 — 행 단위로 자르므로 잘렸을 때 `rows`의 가장 오래된 날은 일부만 담겼을 수 있어, 그날부터는 `obsDate`로 하루씩 받습니다. 이때 `dates`는 조건 없는 전체 거래일 목록(QA-133). 날짜는 `YYYY-MM-DD`, 틀리면 400 |
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
| `GET /api/status` | 수집 현황·신선도·누락 데이터셋. 실패 사유(`detail`)의 비밀값은 가려져 있습니다. 수집기가 꺼져 있으면 DB에서 직접 읽어 **같은 모양**으로 답합니다(`collectorReachable: false`, 키 보유·누락 목록은 빠짐) |
| `GET /api/status/tasks` | 수집 작업 목록 |
| `GET /api/status/history?task=&limit=40` | 태스크 실행 이력 (`limit` 1~200, 비밀값 가림) |
| `POST /api/status/refresh?runFast=true` | 수동 새로고침 |
| `POST /api/status/run/{taskName}` | 태스크 실행을 **시작만** 하고 바로 **202** `{accepted, task, baselineStartedAt}`. 끝났는지는 `/api/status/history?task=&limit=1`의 `startedAt`이 `baselineStartedAt`보다 새로워졌는지로 확인합니다(화면이 3초 간격으로 확인). 없는 태스크 400, 수집기에 닿지 못하면 502 |
| `POST /api/verification` | 교차 검증 (판정 포함) |
| `GET /api/status/issues` | 수집 오류·경고 모음 — 지금 실패 중인 태스크, 최근 24시간 실패 이력(같은 사유는 묶음·횟수), 누락 데이터셋. `text`는 복사용이며 **비밀값이 가려져** 있습니다. 실행 기록은 DB에서 직접 읽어 수집기가 꺼져 있어도 동작하고, 그때 누락 데이터셋(`counts.missingDatasets`·`missing`)은 `null`(모름)이며 `text`에는 '확인 못 함(수집기 연결 안 됨)' |
| `GET /api/status/public-apis` | 국내 공공 API 연결 진단 — **API마다 실제 1회 호출**. 결과에 키 값은 없음 |

### 🧭 국면 · 🔗 상관관계

| 경로 | 설명 |
|---|---|
| `GET /api/analytics/series` | 상관 분석에 쓸 수 있는 계열 목록 (20종) |
| `GET /api/analytics/correlation?x=&y=&window=60&years=3&mode=change` | 롤링 상관계수 + 산점도. `mode=level`에는 허위 상관 경고가 붙습니다. `window`는 5~250, `years`는 1~10으로 접고 응답 `window`는 접은 값(QA-133) |
| `GET /api/analytics/regime?years=5` | 4국면 판정 + 근거 신호 + 주간 이력·구간 |
| `GET /api/analytics/regime/log?limit=90` | 국면 판정 라이브 기록(하루 한 건, 최근 순) — 판정·입력값·입력별 기준일·판정 불가 사유. 추가만 하는 기록(`regime_log`) |
| `GET /api/analytics/regime/track-record?asset=SPY` | 국면 판정 성과 — 매주 목요일 시점 기준 재현 판정 → 다음 거래일부터 4·13·26주 수익률. 국면 시작 시점(`start`)·머문 모든 주(`all`)·비교 기준(`baseline`), 표본 부족이면 수익률 대신 표본 수만. 자산: SPY·QQQ·069500.KS·IEF·GLD |

### 🏦 투자자별 매매 (토스증권 공식)

| 경로 | 설명 |
|---|---|
| `GET /api/kr/investor-flows` | 코스피·코스닥 전체 투자자별 매매대금(원) — 최근일·5일·20일 누적, 연속 일수, 기관 7세부, 20일 계열. 시장마다 `fetchedAt`(그 시장을 받은 시각)·`isStale`(수집 실패로 이전 값을 이어 씀 — `staleReason`·`staleSince`, 3일 지나면 그 시장은 빠짐) |
| `GET /api/kr/stock-flows?codes=005930,000660` | 종목별 투자자 매매동향(주, 20거래일) — 누적·연속 일수·연기금·외국인 보유율 변화. 최대 60개, 형식이 틀린 코드는 무시 |
| `GET /api/krx/spot-futures` | 코스피 현물(토스, 원) × KOSPI200 선물(Daum, 계약) 투자자별 **방향** 비교. 기준일이 다르면 당일 판정 안 함. `spotFetchedAt`(코스피 현물을 받은 시각)·`spotIsStale`(수집 실패로 이전 값을 이어 씀 — `spotStaleReason`·`spotStaleSince`) |

누적 값은 `{sum, days, window}`입니다. `days < window`면 값이 없는 날(당일 잠정치의 null)을 건너뛴 것입니다.

### 📋 전체 원본 데이터

| 경로 | 설명 |
|---|---|
| `GET /api/snapshot/text` | 수집한 전체 대시보드 원본 텍스트 + 생성 시각·분량 (AI 분석 없음). 매크로 화면 상단의 "원본 데이터 보기/복사"가 씁니다 |

### 🤖 AI · 🔌 토스

| 경로 | 설명 |
|---|---|
| `GET /api/ai/engines` | `{engines:[{id, label, provider, model, description, speedHint, available, unavailableReason}], keys, enabled, timeoutSeconds, autoAttemptSeconds, autoBudgetSeconds}`. `available`은 이 배포에서 허용됐고(`AI_ENGINES`, ADR 0014) 키도 있는지이고, 못 쓰면 `unavailableReason`이 `not_allowed`(허용 목록 밖 — 키가 있어도)·`no_key`(쓸 수 있으면 null). `auto`는 허용 목록이 없으면 키가 하나라도 있을 때, 있으면 키가 있는 허용 엔진이 둘 이상일 때만 씁니다. `keys`는 제공자별 키 유무(허용 목록과 무관), `enabled`는 키가 있는 허용 엔진이 하나라도 있는지. 데모 열람에게만 `quota: {used, limit, shared: true}`(방문자 공용 하루 한도의 오늘 사용량) |
| `GET /api/ai/report-types` | 리포트 종류 |
| `POST /api/ai/report` | `{engineId, reportType, extraInstruction}` → 엔진 응답(`status`·`response`·`error`·`provider`·`model`·`failoverPath` 등) + `reportType`·`promptChars`·`quota: {used, limit}`(운영자는 `limit: null`). 하루(KST) 한도를 다 쓰면 엔진을 부르지 않고 200 `{status: false, error, quota}`입니다. 엔진을 네트워크 없이 못 쓰는 경우(AI 꺼짐·모르는 `engineId`·허용 목록 밖·못 쓰는 `auto`(엔진 미지정 포함)·허용됐지만 키 없음)는 **한도를 쓰지 않고 원본 텍스트도 만들지 않고** 바로 엔진 실패(`status: false`, `charged: false`, `error` — 예: "이 배포에서는 쓸 수 없는 엔진입니다: …", "… API Key 누락")에 `reportType`과 쓰지 않은 오늘 `quota`를 붙여 답합니다(`promptChars` 없음). 한도를 다 썼어도 이 판정이 먼저입니다(QA-133). 그 밖의 실패는 **모델에 닿지 않았을 때만**(`charged: false` — 제공자의 HTTP 오류·연결 실패) 한 건을 되돌리고, 거절·빈 응답·응답 대기 시간 초과(`charged: true`)는 되돌리지 않습니다. **데모 열람**은 방문자 전체가 하루 `AI_DEMO_DAILY_LIMIT`건(기본 20, 백엔드 메모리 — 재시작하면 0부터)을 함께 쓰고 `quota`에 `shared: true`가 붙습니다. 다 쓰면 `error`는 "데모의 오늘 AI 리포트 한도(N건)를 모두 썼습니다. 내일(KST 0시) 다시 시도해 주세요." |
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

토큰은 `make setup`이 무작위 값으로 `.env`에 채웁니다. 비어 있으면 수집기가 기동하지
않습니다 — `COLLECTOR_ALLOW_NO_TOKEN=1`을 명시했을 때만 경고를 남기고 토큰 검사 없이 뜹니다(로컬 개발용).
수집기 포트는 `127.0.0.1`에만 열립니다([PRINCIPLES.md](PRINCIPLES.md)).

**브라우저가 보낸 다른 출처의 요청은 토큰과 상관없이 403입니다** — `Sec-Fetch-Site`가
`none`·`same-origin`이 아니거나, 그 헤더 없이 `Origin`이 수집기 자신이 아닌 경우.
대시보드를 켜 둔 채 방문한 다른 사이트가 `/collect` 같은 경로를 부를 수
있었습니다. 백엔드·curl·make는 이 헤더를 보내지 않아 영향이 없습니다.

| 경로 | 설명 |
|---|---|
| `GET /health` | 헬스체크 |
| `GET /status` | 저장 현황 + 키 보유 + 수집 주기 |
| `GET /tasks` | 태스크 목록 |
| `GET /task-history?task=&limit=` | 실행 이력 |
| 🔒 `POST /collect?group=fast\|slow\|weekly\|all&wait=true` | 작업군 실행 |
| 🔒 `POST /collect/task/{taskName}` | 태스크 1건 실행 |
| 🔒 `POST /refresh?scope=global` | 새로고침 기준 시각 갱신 |
| 🔒 `GET /live/radar?...` | 수급 랭킹 즉시 수집 (폴백 체인 전체) |
| 🔒 `GET /live/ticker/{symbol}?period=` | 티커 시계열 즉시 수집 |
| 🔒 `GET /live/daum-intraday?minutes=30` | 장중 수급 가속도 |
| 🔒 `GET /verify/readings?market=&investor=&tradeType=` | 검증용 원자료 (판정은 백엔드, 읽는 코드는 `app/verification.py`) |
| 🔒 `GET /diagnostics/connections` | KIS·LS·Daum·Naver·PyKrx 진단 |
| 🔒 `GET /diagnostics/public-apis` | 특일정보·주식시세·DART 진단 (API당 1회) |
| 🔒 `GET /diagnostics/toss`, `/toss/*` | 토스 진단·조회 |

FastAPI 자동 문서: <http://localhost:8000/docs>
