// scripts/qa/k6/cache-churn.js — 응답 캐시(ComputedCache: 256개 LRU · 60초)가 서로 다른 키에 밀려나는지 잽니다 (QA 스택).
//
// 세 시나리오가 함께 돕니다.
//   hot   : HOT_VUS명(기본 10)이 비싼 키 5개를 돌아가며 1초 간격으로 읽음 — 화면을 보고 있는 사람들(키마다 1초에 한 번,
//           같은 순간에 HOT_VUS/5명)
//   rare  : 1명이 위험 분석 한 조합을 RARE_EVERY초(기본 20)마다 읽음 — 가장 먼저 밀려날 키(가장 민감한 탐침)
//   churn : 초당 RATE번, 화면에서 실제로 고를 수 있는 서로 다른 키를 겹치지 않게 차례로 부름(스코어카드 종목×벤치마크×기간,
//           기관 포트폴리오 분기×종목 수, 기관 위험 벤치마크×기간). 목록은 setup()이 /api/stock/universe와
//           /api/sec13f/institutions에서 만들고, hot·rare 키는 뺍니다. RATE=0이면 churn 없이 기준선만 잽니다.
//
// 캐시에는 적중·실패 카운터가 없어 지연 시간으로 가립니다. setup()이 키마다 한 번 불러 캐시에 올린 뒤 CAL_N번(기본 7) 더
// 불러 적중 지연의 중앙값(warm)을 재고, 문턱 = max(MISS_K × warm, warm + MISS_D)를 넘는 요청을 miss_*(Rate)에 '실패(다시
// 계산)'로 셉니다. 요약은 비율과 건수('1 out of 12' = 12번 읽어 1번 실패)를 함께 보여 줍니다.
//   기본값 MISS_K=3 · MISS_D=20ms: 적중은 동시 50명에서도 p95 5–7ms(docs/qa/evidence/2026-10-18/performance.md)라 중앙값의
//   3배면 적중의 흔들림을 넘고, 20ms는 중앙값이 1–3ms로 작을 때 GC 일시 정지 같은 잡음을 실패로 세지 않게 두는 바닥입니다.
//   둘 다 추정이라 아래 유효성 검사로 확인합니다. 예전처럼 '재시작 직후 첫 요청' 지연(consensus 633–680ms, guru/risk
//   301–320ms, snapshot/text 256–274ms)으로 문턱을 잡으면, 예열 뒤에는 JIT·파싱 캐시가 따뜻해 다시 계산이 문턱보다 빨리
//   끝나 실패를 놓칩니다(거짓 음성). 예열 뒤 다시 계산하는 데 걸리는 시간은 아직 잰 적이 없습니다.
//   보정이 실패하면(요청이 200이 아님, 또는 적중 중앙값이 fallbackMs 이상 — 적중이 그만큼 느리면 그 키는 캐시에 머물지 못한
//   것) 그 키만 예전의 고정 문턱(fallbackMs: 첫 요청 지연보다 낮게 잡은 값)으로 셉니다. 키별 문턱과 근거는 setup 로그와 요약에 나옵니다.
//   miss_ms_*는 실패로 센 요청의 지연입니다. 다시 계산이면 기준선 회차의 값과 비슷하고, 문턱 바로 위에 몰려 있으면 경합으로
//   느려진 적중입니다. guru/profiles(첫 요청 30ms)·portfolio(13–38ms)는 다시 계산해도 적중과 차이가 작아 참고로만 봅니다.
//
// 유효성 검사(RATE=0 기준선 회차): TTL이 60초라 churn이 없어도 키마다 만료가 약 DURATION/60번 나고(rare 키는 RARE_EVERY의
//   배수로 올림한 주기), 그때마다 실패가 납니다. hot 키는 같은 키를 같은 순간에 읽는 VU가 HOT_VUS/5명(기본 2명)이고 캐시가
//   같은 키의 동시 계산을 하나로 묶지 않아, 만료 한 번에 실패가 그 수만큼 날 수 있습니다. 그래서 기본값으로 120초 회차의
//   실패는 hot 키당 1–5건, rare 키 1–3건이어야 합니다.
//   0건이면 다시 계산이 문턱보다 빠른 것(MISS_K·MISS_D를 낮춤), 훨씬 많으면 적중이 문턱을 넘었거나(높임) 회차 중에 저장본이
//   바뀐 것입니다. 이 검사를 통과하지 못한 MISS_K·MISS_D로 잰 churn 회차는 쓰지 않습니다. 요약(handleSummary)이
//   RATE=0이고 DURATION이 충분할 때(기본값 65초 이상) 키별 기대 범위와 통과 여부를 계산해 보여 줍니다(참고 키
//   profiles·portfolio_nps는 판정에서 뺌).
//   handleSummary가 있으면 k6 기본 요약은 나오지 않아, 요약 끝에 지표 전체를 간단히 함께 찍습니다.
//
// 사용(QA 스택에서만 — 운영 스택 8080에 돌리지 않습니다):
//   bash scripts/qa/qa-stack.sh up && bash scripts/qa/qa-stack.sh restore F=backups/….sql   # .env.qa 기본값: 수집 스케줄 꺼짐
//   k6 run -e BASE=http://127.0.0.1:18080 -e ORIGIN=http://localhost:13000 \
//     -e EMAIL="$(grep ^OWNER_EMAIL= .env.qa | cut -d= -f2-)" -e PASSWORD="$(grep ^APP_PASSWORD= .env.qa | cut -d= -f2-)" \
//     -e RATE=5 -e DURATION=120s scripts/qa/k6/cache-churn.js
//   선택: -e HOT_VUS=10 -e RARE_EVERY=20 -e CAL_N=7 -e MISS_K=3 -e MISS_D=20
//
// 절차: 백엔드 재시작(bash scripts/qa/qa-stack.sh compose restart backend) → api-load.js 50명 30초 예열(버림) →
//   RATE 0, 1, 2, 5, 10, 20, 50 각각 120초 × 3회, 중앙값. RATE=0 회차가 유효성 검사를 통과하는지 먼저 봅니다. 회차 앞뒤로
//   저장본 버전이 같은지 봅니다 — 어떤 저장본이든 새로 쓰이면 캐시 전체가 버려져 그 회차는 쓸 수 없습니다:
//     bash scripts/qa/qa-stack.sh exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT max(collected_at) FROM snapshots"'
//   다르면 그 회차는 버리고 .env.qa의 DASHBOARD_READ_MODE=store_only로 다시 띄워 잽니다(조회가 수집을 일으키지 않게).
// 판정(제안) — 1차 기준은 실패 건수이고, hot p95는 2차입니다.
//   1차: 키별 실패 건수(3회 중앙값)를 RATE=0 기준선과 비교합니다. RATE≤2에서 기준선보다 키당 1건 넘게 늘면 캐시 구성을 고칠
//     근거가 됩니다. 다만 LRU 산술로는 hot 키(1초 간격)가 밀려나려면 churn이 초당 약 256개는 돼야 하고, rare 키(20초
//     간격)도 RATE≈13(256/20초) 근처에서야 밀리기 시작한다고 봅니다(추정). 그래서 hot 키는 이 시험의 대조군이기도 합니다.
//     rare만 늘면 밀려남이고, hot까지 늘면 먼저 miss_ms_*를 봅니다 — 기준선 회차의 실패 지연과 비슷하면 진짜 다시 계산(캐시가
//     산술대로 움직이지 않음), 문턱 바로 위에 몰려 있으면 경합(churn의 차가운 계산이 같은 JVM의 CPU·GC를 씀)입니다.
//   2차: hot p95는 실패 건수가 늘었을 때 그 영향이 얼마나 큰지 보는 데만 씁니다. 실패 건수는 그대로인데 p95만 오르면 경합이지
//     밀려남이 아니어서, 캐시 구성(크기·접두사별 상한)을 바꿔도 고쳐지지 않습니다.
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
const CAL_N = Number(__ENV.CAL_N || 7);       // 보정: 첫 요청 뒤 적중 지연을 몇 번 잴지
const MISS_K = Number(__ENV.MISS_K || 3);     // 보정: 문턱 = max(MISS_K × 적중 중앙값, 적중 중앙값 + MISS_D)
const MISS_D = Number(__ENV.MISS_D || 20);    // ms
const TTL_SECONDS = 60;                       // ComputedCache.MAX_AGE_SECONDS
if (!(CAL_N >= 1) || !(MISS_K >= 1) || !(MISS_D >= 0)) throw new Error('CAL_N≥1 · MISS_K≥1 · MISS_D≥0 이어야 합니다');

const NPS = '0001608046';        // 국민연금
const BERKSHIRE = '0001067983';  // 버크셔 해서웨이
const BENCHMARKS = ['SPY', 'QQQ', 'ACWI'];   // 화면의 벤치마크 선택지(Benchmarks.TICKERS)
const YEARS = [1, 2, 3, 5];                  // 화면의 측정 구간 선택지(스코어카드·기관 위험)
const QUARTERS = [1, 2, 4, 8];               // 기관 포트폴리오 화면의 분기 수 선택지
const TOP_N = [10, 20, 30, 50];              // 기관 포트폴리오 화면의 종목 수 선택지

// fallbackMs: 보정이 실패한 키만 쓰는 고정 문턱(재시작 직후 첫 요청 지연보다 낮게 잡은 값). ref: 참고로만 보는 키.
const HOT = [
  { name: 'snapshot_text', path: '/api/snapshot/text', fallbackMs: 100 },
  { name: 'consensus', path: '/api/sec13f/consensus?minHolders=2', fallbackMs: 150 },
  { name: 'risk_nps', path: `/api/guru/risk?cik=${NPS}&benchmark=SPY&years=1`, fallbackMs: 100 },
  { name: 'profiles', path: '/api/guru/profiles', fallbackMs: 20, ref: true },
  { name: 'portfolio_nps', path: `/api/sec13f/portfolio?cik=${NPS}&quarters=8&topN=30`, fallbackMs: 15, ref: true },
];
const RARE = { name: 'risk_berkshire', path: `/api/guru/risk?cik=${BERKSHIRE}&benchmark=QQQ&years=3`, fallbackMs: 100 };

const metrics = {};
for (const k of HOT) {
  metrics[k.name] = { t: new Trend(`hot_${k.name}`, true), miss: new Rate(`miss_${k.name}`), missMs: new Trend(`miss_ms_${k.name}`, true) };
}
metrics[RARE.name] = {
  t: new Trend(`rare_${RARE.name}`, true), miss: new Rate(`miss_rare_${RARE.name}`), missMs: new Trend(`miss_ms_${RARE.name}`, true),
};
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
    const m = metrics[key.name];
    const ms = res.timings.duration;
    const miss = ms > data.cal[key.name].missMs;
    m.t.add(ms);
    m.miss.add(miss);
    if (miss) m.missMs.add(ms);
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

function median(xs) {
  if (xs.length === 0) return NaN;
  const s = [...xs].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
}

const fmt = (x) => (Number.isFinite(x) ? x.toFixed(1) : '—');

// 키 하나의 문턱: 한 번 불러 캐시에 올리고(첫 요청이 다시 계산이어도 됨) CAL_N번 더 불러 적중 지연의 중앙값을 잽니다.
// 중앙값이라 보정 중에 마침 TTL이 끝나 한 번 다시 계산하더라도 흔들리지 않습니다. setup()이 돌려주는 값은 JSON으로 넘어가
// NaN을 담을 수 없어, 모르는 값은 null로 둡니다.
function calibrate(cookie, key) {
  const warmSamples = [];
  let firstMs = null;
  for (let i = 0; i <= CAL_N; i++) {
    const res = http.get(`${BASE}${key.path}`, { cookies: { macro_session: cookie }, tags: { name: 'calibrate' }, timeout: '60s' });
    if (res.status !== 200) return { missMs: key.fallbackMs, source: 'fallback', why: `보정 요청이 ${res.status}`, firstMs, warmMs: null };
    if (i === 0) firstMs = res.timings.duration;
    else warmSamples.push(res.timings.duration);
  }
  const warmMs = median(warmSamples);
  if (!(warmMs < key.fallbackMs)) {
    return { missMs: key.fallbackMs, source: 'fallback', why: `적중 중앙값이 고정 문턱 ${key.fallbackMs}ms 이상`, firstMs, warmMs };
  }
  return { missMs: Math.max(MISS_K * warmMs, warmMs + MISS_D), source: 'calibrated', why: '', firstMs, warmMs };
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

  const cal = {};
  for (const k of [...HOT, RARE]) {
    const c = calibrate(cookie, k);
    cal[k.name] = c;
    const line = `문턱 ${k.name}: ${fmt(c.missMs)}ms (${c.source === 'calibrated' ? '보정' : `고정 — ${c.why}`}) · `
      + `적중 중앙값 ${fmt(c.warmMs)}ms(${CAL_N}번) · 첫 요청 ${fmt(c.firstMs)}ms`;
    if (c.source === 'calibrated') console.log(line);
    else console.warn(line);
  }
  return { cookie, churn, cal };
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

// k6 기간 문자열('120s', '2m', '1m30s')을 초로. 단위 없는 값처럼 읽을 수 없으면 NaN(유효성 검사를 건너뜀).
function durationSeconds(s) {
  const str = String(s).trim();
  if (!/^(\d+(\.\d+)?(ms|s|m|h))+$/.test(str)) return NaN;
  const unit = { ms: 0.001, s: 1, m: 60, h: 3600 };
  let total = 0;
  for (const m of str.matchAll(/(\d+(\.\d+)?)(ms|s|m|h)/g)) total += Number(m[1]) * unit[m[3]];
  return total;
}

// RATE=0 회차에서 키마다 기대하는 실패 건수 범위: 만료 횟수(DURATION / 만료 주기) × 같은 순간에 읽는 VU 수, 앞뒤로 1건 여유.
// 만료 주기는 hot 키가 TTL(1초마다 읽어 거의 그대로), rare 키는 TTL을 RARE_EVERY의 배수로 올린 값입니다(20초면 60초 = 세 번째 읽기).
const RARE_PERIOD_S = Math.ceil(TTL_SECONDS / RARE_EVERY) * RARE_EVERY;
// 이보다 짧으면 만료 뒤 읽기가 한 번도 없을 수 있어 0건이 정상일 수 있습니다(응답 시간 여유 5초).
const BASELINE_MIN_S = Math.max(TTL_SECONDS, RARE_PERIOD_S) + 5;
function baselineRange(durationS, periodS, readers) {
  const expiries = durationS / periodS;
  return { lo: Math.max(1, Math.floor(expiries) - 1), hi: Math.ceil(expiries) * readers + 1 };
}

// 한글은 터미널에서 두 칸을 차지해, 표가 어긋나지 않게 폭을 세어 채웁니다.
const cellWidth = (s) => [...s].reduce((w, ch) => w + (/[\u1100-\u115f\u3130-\u318f\uac00-\ud7a3]/.test(ch) ? 2 : 1), 0);
const padRight = (s, n) => s + ' '.repeat(Math.max(0, n - cellWidth(s)));
const padLeft = (s, n) => ' '.repeat(Math.max(0, n - cellWidth(s))) + s;
const TABLE = [['키', 24, padRight], ['문턱ms', 8, padLeft], ['근거', 5, padLeft], ['적중ms', 7, padLeft], ['읽음', 6, padLeft],
  ['실패', 5, padLeft], ['실패ms med', 11, padLeft], ['p95ms', 8, padLeft], ['기준선 검사', 0, (x) => `  ${x}`]];
const tableRow = (cells) => `  ${cells.map((c, i) => TABLE[i][2](c, TABLE[i][1])).join('')}`;

function metricLine(name, mm) {
  const v = mm.values;
  let text;
  if (mm.type === 'trend') {
    const unit = mm.contains === 'time' ? 'ms' : '';
    const order = (s) => (options.summaryTrendStats.indexOf(s) + 1) || 99;
    text = Object.keys(v).sort((a, b) => order(a) - order(b))
      .map((s) => (s === 'count' ? `count=${v[s]}` : `${s}=${fmt(v[s])}${unit}`)).join(' ');
  } else if (mm.type === 'rate') text = `${(v.rate * 100).toFixed(2)}% ${v.passes} out of ${v.passes + v.fails}`;
  else if (mm.type === 'counter') text = `${v.count} (${fmt(v.rate)}/s)`;
  else text = Object.entries(v).map(([s, x]) => `${s}=${x}`).join(' ');
  const th = Object.entries(mm.thresholds || {}).map(([expr, r]) => `${r.ok ? '✓' : '✗'} ${expr}`).join(' ');
  return `  ${padRight(name, 28)} ${text}${th ? `   [${th}]` : ''}`;
}

export function handleSummary(data) {
  const m = data.metrics;
  const cal = (data.setup_data && data.setup_data.cal) || {};
  const durationS = durationSeconds(DURATION);
  const checkBaseline = RATE === 0 && durationS >= BASELINE_MIN_S;
  const lines = [
    `cache-churn 요약 — RATE=${RATE}/s · DURATION=${DURATION} · 문턱 = max(${MISS_K} × 적중 중앙값, 적중 중앙값 + ${MISS_D}ms)`,
    '',
    tableRow(TABLE.map((c) => c[0])),
  ];
  let baselineOk = true;
  for (const k of [...HOT, RARE]) {
    const isRare = k === RARE;
    const t = m[`${isRare ? 'rare' : 'hot'}_${k.name}`];
    const miss = m[isRare ? `miss_rare_${k.name}` : `miss_${k.name}`];
    const missMs = m[`miss_ms_${k.name}`];
    const c = cal[k.name] || {};
    const misses = miss ? miss.values.passes : 0;
    let verdict = '—';
    if (checkBaseline) {
      const { lo, hi } = isRare
        ? baselineRange(durationS, RARE_PERIOD_S, 1)
        : baselineRange(durationS, TTL_SECONDS, Math.ceil(HOT_VUS / HOT.length));
      const ok = misses >= lo && misses <= hi;
      if (!ok && !k.ref) baselineOk = false;
      verdict = `${ok ? '✓' : '✗'} ${lo}–${hi}건${k.ref ? ' (참고 — 판정에서 뺌)' : ''}`;
    }
    lines.push(tableRow([`${k.name}${isRare ? ' (rare)' : ''}${k.ref ? ' (참고)' : ''}`, fmt(c.missMs),
      c.source === 'calibrated' ? '보정' : '고정', fmt(c.warmMs), String(t ? t.values.count : 0), String(misses),
      fmt(missMs ? missMs.values.med : NaN), fmt(t ? t.values['p(95)'] : NaN), verdict]));
  }
  lines.push('');
  if (checkBaseline) {
    lines.push(baselineOk
      ? '기준선 유효 — 실패 건수가 TTL 만료로 기대하는 범위 안입니다. 같은 MISS_K·MISS_D로 churn 회차를 잽니다.'
      : '기준선 무효 — 0건 쪽이면 다시 계산이 문턱보다 빠른 것(MISS_K·MISS_D를 낮춤), 많은 쪽이면 적중이 문턱을 넘었거나'
        + ' 회차 중에 저장본이 바뀐 것(버전 확인·문턱 높임)입니다. 이 문턱으로 잰 churn 회차는 쓰지 않습니다.');
  } else if (RATE === 0) {
    lines.push(`기준선 검사는 DURATION이 ${BASELINE_MIN_S}초 이상일 때만 합니다(그보다 짧으면 만료 뒤 읽기가 없어 0건이 정상일 수 있음).`);
  } else {
    lines.push('실패 건수는 같은 MISS_K·MISS_D로 잰 RATE=0 기준선 회차와 비교합니다(판정 순서는 파일 머리말). hot 실패가 늘면'
      + ' 실패ms med가 기준선의 값과 비슷한지(다시 계산) 문턱 바로 위인지(경합) 봅니다.');
  }
  lines.push('', '지표 전체:');
  for (const name of Object.keys(m).sort()) lines.push(metricLine(name, m[name]));
  return { stdout: `${lines.join('\n')}\n` };
}
