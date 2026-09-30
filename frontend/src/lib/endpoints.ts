/**
 * src/lib/endpoints.ts
 * 백엔드 API 경로를 한 곳에 모읍니다. 화면은 주소 문자열을 직접 만들지 않고 여기 함수를 씁니다.
 *
 * 왜 — 예전에는 31개 파일이 주소를 제각각 만들었습니다. 같은 값을 어떤 곳은 인코딩하고
 * 어떤 곳은 그대로 붙여(`&`·공백이 들어가면 요청이 깨짐), 경로가 바뀌면 여러 파일을 찾아
 * 고쳐야 했습니다. 묶음은 백엔드 기능 패키지(feature/*)와 같게 나눕니다.
 *
 * 규칙
 * - 모든 쿼리 값과 경로 조각은 encodeURIComponent로 인코딩합니다.
 * - 필수 값은 비어 있어도 보냅니다(`ids=`) — 서버가 "비었음"을 판단합니다.
 * - 선택 값은 undefined면 뺍니다. 호출하는 쪽이 "없음"을 undefined로 넘깁니다.
 *
 * 경로 목록은 docs/API.md 백엔드 표와 테스트로 대조합니다(__tests__/endpoints.test.ts).
 */

type QueryValue = string | number | boolean | undefined;

/** 경로 + 쿼리스트링. undefined인 값만 뺍니다(순서 유지). */
function withQuery(path: string, params: Record<string, QueryValue>): string {
  const parts = Object.entries(params)
    .filter(([, value]) => value !== undefined)
    .map(([key, value]) => `${key}=${encodeURIComponent(String(value))}`);
  return parts.length > 0 ? `${path}?${parts.join("&")}` : path;
}

const segment = (value: string) => encodeURIComponent(value);

/** 비어 있으면 undefined — 선택 값으로 넘길 때 씁니다. */
const optional = (value: string | null | undefined) => (value ? value : undefined);

export const endpoints = {
  auth: {
    login: "/api/auth/login",
    logout: "/api/auth/logout",
    session: "/api/auth/session",
  },

  // 📊 매크로 · 순유동성 · 로테이션
  macro: {
    overview: (live: boolean) => withQuery("/api/macro/overview", { live: live ? true : undefined }),
    risk: "/api/macro/risk",
    advanced: "/api/macro/advanced",
    usdkrw: "/api/macro/usdkrw",
    scraped: "/api/macro/scraped",
    fx: (ids: readonly string[], period: string, mode: string) =>
      withQuery("/api/macro/fx", { ids: ids.join(","), period, mode }),
    fred: (seriesId: string, years: number) => withQuery(`/api/macro/fred/${segment(seriesId)}`, { years }),
    ticker: (symbol: string, period: string) => withQuery("/api/macro/ticker", { symbol, period }),
    liquidity: (years: string | number) => withQuery("/api/liquidity", { years }),
    sectorRotation: (period: string) => withQuery("/api/sector/rotation", { period }),
  },

  // 📑 13F · 🧬 기관 스타일
  institution: {
    institutions: "/api/sec13f/institutions",
    portfolio: (cik: string, quarters: string | number, topN: string | number) =>
      withQuery("/api/sec13f/portfolio", { cik, quarters, topN }),
    consensus: (p: { minHolders: string | number; topN: number; ciks?: string; reportDate?: string }) =>
      withQuery("/api/sec13f/consensus", {
        minHolders: p.minHolders, topN: p.topN, ciks: optional(p.ciks), reportDate: optional(p.reportDate),
      }),
    newBuys: (minHolders: string | number, reportDate?: string) =>
      withQuery("/api/sec13f/new-buys", { minHolders, reportDate: optional(reportDate) }),
    guruProfiles: "/api/guru/profiles",
    guruSimilarity: "/api/guru/similarity",
    guruHolders: (q: string) => withQuery("/api/guru/holders", { q }),
    guruRisk: (cik: string, benchmark: string, years: string | number) =>
      withQuery("/api/guru/risk", { cik, benchmark, years }),
  },

  // 🩺 스코어카드 · 🔗 상관 · 🧭 국면
  insight: {
    stockUniverse: "/api/stock/universe",
    scorecard: (symbol: string, benchmark: string, years: string | number) =>
      withQuery("/api/stock/scorecard", { symbol, benchmark, years }),
    series: "/api/analytics/series",
    correlation: (p: { x: string; y: string; window: string | number; years: string | number; mode: string }) =>
      withQuery("/api/analytics/correlation", { x: p.x, y: p.y, window: p.window, years: p.years, mode: p.mode }),
    regime: (years: string | number) => withQuery("/api/analytics/regime", { years }),
  },

  // 🏛️ COT · 🇰🇷 파생 · 📡 레이더 · 🏦 투자자별 매매
  positioning: {
    cotAssets: "/api/cot/assets",
    cotOverview: "/api/cot/overview",
    cotAsset: (name: string) => withQuery("/api/cot/asset", { name }),
    cotExtremes: (name: string, percentile: string | number, lookbackWeeks: string | number) =>
      withQuery("/api/cot/extremes", { name, percentile, lookbackWeeks }),
    krxFutures: (days: number) => withQuery("/api/krx/futures", { days }),
    krxInvestorTrend: "/api/krx/investor-trend",
    krxIntraday: (minutes: number) => withQuery("/api/krx/intraday", { minutes }),
    krxSpotFutures: "/api/krx/spot-futures",
    radarOptions: "/api/radar/options",
    radarRanking: (p: { market: string; investor: string; tradeType: string; intervalType: string; topN: string | number }) =>
      withQuery("/api/radar/ranking", {
        market: p.market, investor: p.investor, tradeType: p.tradeType, intervalType: p.intervalType, topN: p.topN,
      }),
    radarConsensus: (p: { market: string; tradeType: string; intervalType: string; topN: string | number }) =>
      withQuery("/api/radar/consensus", {
        market: p.market, tradeType: p.tradeType, intervalType: p.intervalType, topN: p.topN,
      }),
    /** 하루치 이력. obsDate가 없으면 가장 최근 날짜. */
    radarHistory: (p: { market: string; investor: string; tradeType: string; obsDate?: string }) =>
      withQuery("/api/radar/history", {
        market: p.market, investor: p.investor, tradeType: p.tradeType, latest: true, obsDate: optional(p.obsDate),
      }),
    radarDiagnostics: "/api/radar/diagnostics",
    investorFlows: "/api/kr/investor-flows",
    stockFlows: (codes: string) => withQuery("/api/kr/stock-flows", { codes }),
  },

  // 공공 API(공공데이터포털·Open DART)
  publicData: {
    krHolidays: "/api/calendar/kr-holidays",
    fundamentals: (codes: string) => withQuery("/api/kr/fundamentals", { codes }),
    marketTotals: (days: number) => withQuery("/api/kr/market-totals", { days }),
    /** run은 누를 때마다 바꿔 새로 부르게 하는 값입니다(서버는 쓰지 않음). */
    apiStatus: (run: number) => withQuery("/api/status/public-apis", { run }),
  },

  // 🗄️ 저장소 상태 · 교차 검증
  status: {
    overview: "/api/status",
    issues: "/api/status/issues",
    refresh: (runFast: boolean) => withQuery("/api/status/refresh", { runFast }),
    runTask: (taskName: string) => `/api/status/run/${segment(taskName)}`,
    history: (task: string, limit: number) => withQuery("/api/status/history", { task, limit }),
    verification: "/api/verification",
  },

  // 📋 전체 원본 데이터
  snapshot: {
    text: "/api/snapshot/text",
  },

  // 🤖 AI
  ai: {
    engines: "/api/ai/engines",
    reportTypes: "/api/ai/report-types",
    report: "/api/ai/report",
    test: (engineId: string, prompt: string) => withQuery("/api/ai/test", { engineId, prompt }),
  },

  // 🔌 토스증권 연결 테스트 (경로는 /api/ai/toss 이지만 AI와 관계없음)
  toss: {
    diagnostics: (run: number) => withQuery("/api/ai/toss/diagnostics", { run }),
    exchangeRate: (base: string, quote: string) => withQuery("/api/ai/toss/exchange-rate", { base, quote }),
    indices: (symbols: string) => withQuery("/api/ai/toss/indices", { symbols }),
  },
} as const;
