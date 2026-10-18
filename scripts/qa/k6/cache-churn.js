// scripts/qa/k6/cache-churn.js — 응답 캐시(ComputedCache: 256개 LRU · 60초)가 서로 다른 키에 밀려나는지 잽니다 (QA 스택).
//
// 세 시나리오가 함께 돕니다.
//   hot   : HOT_VUS명(기본 10)이 비싼 키 5개를 돌아가며 1초 간격으로 읽음 — 화면을 보고 있는 사람들
//   rare  : 1명이 위험 분석 한 조합을 RARE_EVERY초(기본 20)마다 읽음 — 가장 먼저 밀려날 키(가장 민감한 탐침)
//   churn : 초당 RATE번, 화면에서 실제로 고를 수 있는 서로 다른 키를 겹치지 않게 차례로 부름(스코어카드 종목×벤치마크×기간,
//           기관 포트폴리오 분기×종목 수, 기관 위험 벤치마크×기간). 목록은 setup()이 /api/stock/universe와
//           /api/sec13f/institutions에서 만들고, hot·rare 키는 뺍니다. RATE=0이면 churn 없이 기준선만 잽니다.
//
// 캐시에는 적중·실패 카운터가 없어 지연 시간으로 가립니다. 키마다 Trend(hot_*·rare_*)를 두고, 차갑게 계산할 때의
// 지연(docs/qa/evidence/2026-10-18/performance.md — consensus 633–680ms, guru/risk 301–320ms, snapshot/text 256–274ms;
// 따뜻하면 p95 5–7ms)보다 낮게 잡은 기준(missMs)을 넘으면 miss_*(Rate)에 '실패'로 셉니다. 요약의 miss_*는 비율과
// 건수('1 out of 12' = 12번 읽어 1번 실패)를 함께 보여 줍니다. guru/profiles(차갑게 30ms)·portfolio(13–38ms)는 따뜻할 때와
// 차이가 작아 참고로만 봅니다. rare 키(버크셔·QQQ·3년)의 차가운 지연은 재지 않았고 국민연금 위험과 비슷하다고 봅니다.
// TTL이 60초라 churn이 없어도(RATE=0) hot 키는 키마다 대략 DURATION/60번, rare 키는 3번에 1번꼴로 실패가 납니다 — 이것이 기준선입니다.
//
// 사용(QA 스택에서만 — 운영 스택 8080에 돌리지 않습니다):
//   bash scripts/qa/qa-stack.sh up && bash scripts/qa/qa-stack.sh restore F=backups/….sql   # .env.qa 기본값: 수집 스케줄 꺼짐
//   k6 run -e BASE=http://127.0.0.1:18080 -e ORIGIN=http://localhost:13000 \
//     -e EMAIL="$(grep ^OWNER_EMAIL= .env.qa | cut -d= -f2-)" -e PASSWORD="$(grep ^APP_PASSWORD= .env.qa | cut -d= -f2-)" \
//     -e RATE=5 -e DURATION=120s scripts/qa/k6/cache-churn.js
//   선택: -e HOT_VUS=10 -e RARE_EVERY=20
//
// 절차: 백엔드 재시작(bash scripts/qa/qa-stack.sh compose restart backend) → api-load.js 50명 30초 예열(버림) →
//   RATE 0, 1, 2, 5, 10, 20, 50 각각 120초 × 3회, 중앙값. 회차 앞뒤로 저장본 버전이 같은지 봅니다 — 어떤 저장본이든 새로
//   쓰이면 캐시 전체가 버려져 그 회차는 쓸 수 없습니다:
//     bash scripts/qa/qa-stack.sh exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT max(collected_at) FROM snapshots"'
//   다르면 그 회차는 버리고 .env.qa의 DASHBOARD_READ_MODE=store_only로 다시 띄워 잽니다(조회가 수집을 일으키지 않게).
// 판정(제안): RATE≤2에서 hot 키(snapshot_text·consensus·risk_nps) 실패가 기준선보다 키당 1건 넘게 늘거나 hot p95가 20% 넘게
//   오르면 캐시 구성을 고칠 근거가 됩니다. LRU 산술로는 rare 키가 RATE≈13(256/20초) 근처에서야 밀리기 시작한다고 봅니다(추정).
import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://127.0.0.1:18080';
const ORIGIN = __ENV.ORIGIN || 'http://localhost:13000';
const RATE = Number(__ENV.RATE || 0);
const DURATION = __ENV.DURATION || '120s';
const HOT_VUS = Number(__ENV.HOT_VUS || 10);
const RARE_EVERY = Number(__ENV.RARE_EVERY || 20);

const NPS = '0001608046';        // 국민연금
const BERKSHIRE = '0001067983';  // 버크셔 해서웨이
const BENCHMARKS = ['SPY', 'QQQ', 'ACWI'];   // 화면의 벤치마크 선택지(Benchmarks.TICKERS)
const YEARS = [1, 2, 3, 5];                  // 화면의 측정 구간 선택지(스코어카드·기관 위험)
const QUARTERS = [1, 2, 4, 8];               // 기관 포트폴리오 화면의 분기 수 선택지
const TOP_N = [10, 20, 30, 50];              // 기관 포트폴리오 화면의 종목 수 선택지

// missMs: 이보다 느리면 캐시 실패(다시 계산)로 셉니다.
const HOT = [
  { name: 'snapshot_text', path: '/api/snapshot/text', missMs: 100 },
  { name: 'consensus', path: '/api/sec13f/consensus?minHolders=2', missMs: 150 },
  { name: 'risk_nps', path: `/api/guru/risk?cik=${NPS}&benchmark=SPY&years=1`, missMs: 100 },
  { name: 'profiles', path: '/api/guru/profiles', missMs: 20 },
  { name: 'portfolio_nps', path: `/api/sec13f/portfolio?cik=${NPS}&quarters=8&topN=30`, missMs: 15 },
];
const RARE = { name: 'risk_berkshire', path: `/api/guru/risk?cik=${BERKSHIRE}&benchmark=QQQ&years=3`, missMs: 100 };

const metrics = {};
for (const k of HOT) metrics[k.name] = { t: new Trend(`hot_${k.name}`, true), miss: new Rate(`miss_${k.name}`) };
metrics[RARE.name] = { t: new Trend(`rare_${RARE.name}`, true), miss: new Rate(`miss_rare_${RARE.name}`) };
const churnTime = new Trend('churn_req', true);

const scenarios = {
  hot: { executor: 'constant-vus', exec: 'hot', vus: HOT_VUS, duration: DURATION },
  rare: { executor: 'constant-vus', exec: 'rare', vus: 1, duration: DURATION },
};
if (RATE > 0) {
  scenarios.churn = {
    executor: 'constant-arrival-rate', exec: 'churn', rate: RATE, timeUnit: '1s', duration: DURATION,
    preAllocatedVUs: Math.max(5, RATE * 2), maxVUs: Math.max(20, RATE * 10),
  };
}

export const options = {
  scenarios,
  thresholds: { http_req_failed: ['rate<0.01'] },
  summaryTrendStats: ['count', 'med', 'p(95)', 'max'],
};

function get(data, path, name) {
  // 경로마다 시계열이 생기지 않게 name 태그로 묶습니다(churn 키는 수천 개).
  return http.get(`${BASE}${path}`, { cookies: { macro_session: data.cookie }, tags: { name }, timeout: '60s' });
}

function record(data, key) {
  const res = get(data, key.path, key.name);
  check(res, { 'status 200': (r) => r.status === 200 });
  if (res.status === 200) {
    metrics[key.name].t.add(res.timings.duration);
    metrics[key.name].miss.add(res.timings.duration > key.missMs);
  }
}

function getJson(cookie, path) {
  const res = http.get(`${BASE}${path}`, { cookies: { macro_session: cookie } });
  if (res.status !== 200) throw new Error(`${path}: ${res.status}`);
  return res.json();
}

// 종류가 섞이게 번갈아 꺼냅니다(스코어카드만 몰리지 않게).
function interleave(lists) {
  const out = [];
  const longest = Math.max(...lists.map((l) => l.length));
  for (let i = 0; i < longest; i++) for (const l of lists) if (i < l.length) out.push(l[i]);
  return out;
}

export function setup() {
  const res = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email: __ENV.EMAIL || '', password: __ENV.PASSWORD || '' }),
    { headers: { 'Content-Type': 'application/json', Origin: ORIGIN } });
  if (res.status !== 200) throw new Error(`login failed: ${res.status}`);
  const cookie = res.cookies.macro_session && res.cookies.macro_session[0] ? res.cookies.macro_session[0].value : null;
  if (!cookie) throw new Error('no session cookie');

  // 두 목록은 응답 캐시를 거치지 않는 경로라 측정 전에 캐시를 채우지 않습니다.
  const tickers = (getJson(cookie, '/api/stock/universe').rows || []).map((r) => r.ticker).filter(Boolean);
  const ciks = (getJson(cookie, '/api/sec13f/institutions').institutions || []).map((i) => i.cik).filter(Boolean);
  if (tickers.length === 0) throw new Error('스코어카드 종목 목록이 비었습니다 — 백업을 복원했는지 보세요');
  for (const cik of [NPS, BERKSHIRE]) if (!ciks.includes(cik)) throw new Error(`기관 목록에 ${cik}가 없습니다`);

  const fixed = new Set([...HOT.map((k) => k.path), RARE.path]);
  const scorecards = [];
  const portfolios = [];
  const risks = [];
  for (const t of tickers) for (const b of BENCHMARKS) for (const y of YEARS) {
    scorecards.push(`/api/stock/scorecard?symbol=${encodeURIComponent(t)}&benchmark=${b}&years=${y}`);
  }
  for (const c of ciks) {
    for (const q of QUARTERS) for (const n of TOP_N) portfolios.push(`/api/sec13f/portfolio?cik=${c}&quarters=${q}&topN=${n}`);
    for (const b of BENCHMARKS) for (const y of YEARS) risks.push(`/api/guru/risk?cik=${c}&benchmark=${b}&years=${y}`);
  }
  const churn = interleave([scorecards, portfolios, risks]).filter((p) => !fixed.has(p));
  console.log(`churn 키 ${churn.length}개 (스코어카드 ${scorecards.length} · 포트폴리오 ${portfolios.length} · 위험 ${risks.length}, `
    + `hot·rare 제외), RATE=${RATE}/s, DURATION=${DURATION}`);
  if (RATE > 0 && churn.length <= 256) console.warn('churn 키가 캐시 크기(256) 이하라 같은 키가 다시 적중할 수 있습니다');
  return { cookie, churn };
}

export function hot(data) {
  record(data, HOT[(__VU + __ITER) % HOT.length]);
  sleep(1);
}

export function rare(data) {
  record(data, RARE);
  sleep(RARE_EVERY);
}

export function churn(data) {
  // 시나리오 전체에서 겹치지 않는 반복 번호로 다음 키를 고릅니다 — 목록을 다 돌기 전에는 같은 키를 다시 부르지 않습니다.
  const path = data.churn[exec.scenario.iterationInTest % data.churn.length];
  const res = get(data, path, 'churn');
  check(res, { 'status 200': (r) => r.status === 200 });
  churnTime.add(res.timings.duration);
}
