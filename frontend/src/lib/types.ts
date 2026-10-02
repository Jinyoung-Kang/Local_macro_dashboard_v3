/**
 * src/lib/types.ts
 * 백엔드 응답 계약.
 *
 * 값이 없을 수 있는 필드는 전부 `| null`입니다. 프런트는 그 null을 그대로
 * "—"로 표시하고, 절대 0으로 바꾸지 않습니다.
 */

export interface MacroItem {
  key: string;
  name: string;
  note?: string | null;
  /** 거래되는 시장 id — 개장/마감 판정용 (lib/marketSessions MARKETS의 키) */
  market?: string | null;
  ticker?: string | null;
  status: "ok" | "single" | "fail";
  price?: number | null;
  priceStr?: string | null;
  delta?: number | null;
  pct?: number | null;
  deltaStr?: string | null;
  prevStr?: string | null;
  prevValue?: number | null;
  prevSource?: string | null;
  lastTs?: string | null;
  source?: string | null;
  isReference?: boolean;
}

export interface MacroCategory {
  id: string;
  title: string;
  note?: string | null;
  items: MacroItem[];
}

export interface SpreadBlock {
  longId: string;
  shortId: string;
  points: { date: string; value: number }[];
  latest: number | null;
  previous: number | null;
  /** 같은 쌍을 스크래핑 시세로 계산한 "지금" 값. 공식 확정치와 함께 보여 줍니다. */
  scraped?: ScrapedSpread;
}

export interface MacroOverview {
  available: boolean;
  readMode: string;
  message?: string;
  collectedAtKst?: string;
  ageSeconds?: number;
  stale?: boolean;
  categories: MacroCategory[];
  rates?: Record<string, { current: number | null; previous: number | null }>;
  spreads?: {
    realtime: ScrapedSpread;
    official10y2y: SpreadBlock;
    official30y2y: SpreadBlock;
  };
}

/** 스크래핑 수익률로 계산한 "지금" 스프레드 (공식 확정치와 성격이 다릅니다). */
export interface ScrapedSpread {
  /** 만기 키(us02y·us10y·us30y). 값 자체는 longValue/shortValue에만 있습니다. */
  longKey?: string;
  shortKey?: string;
  longValue: number | null;
  shortValue: number | null;
  spread: number | null;
  previousSpread: number | null;
  delta: number | null;
}

export interface RiskEntry {
  available: boolean;
  label?: string;
  unit?: string;
  value?: number | null;
  previous?: number | null;
  delta?: number | null;
  pct?: number | null;
  asOf?: string | null;
  percentile?: number | null;
  isProxy?: boolean;
  sourceLabel?: string | null;
  points?: { date: string; value: number }[];
}

export interface RiskIndicators {
  vix: RiskEntry;
  move: RiskEntry;
  hyOas: RiskEntry;
  cpSpread: RiskEntry;
  stlfsi: RiskEntry;
}

export interface AdvancedEntry {
  id: string;
  label: string;
  unit: string;
  digits: number;
  group: string;
  why: string;
  source: string;
  available: boolean;
  value?: number | null;
  prev?: number | null;
  delta?: number | null;
  asOf?: string | null;
  percentile?: number | null;
  status?: string;
  color?: string;
  note?: string;
}

export interface AdvancedIndicators {
  order: string[];
  latest: Record<string, AdvancedEntry>;
  derived: { impliedNominal10y?: number; decomposition?: string };
}

export interface LiquidityRow {
  date: string;
  walcl: number;
  wtregen: number;
  rrpM: number;
  rrpB: number;
  netLiquidityM: number;
  netLiquidityT: number;
  walclT: number;
  wtregenB: number;
}

export interface LiquidityResponse {
  available: boolean;
  isEstimated?: boolean;
  message?: string;
  collectedAtKst?: string;
  ageSeconds?: number;
  stale?: boolean;
  rows: LiquidityRow[];
  latest?: {
    date?: string;
    netLiquidityT: number | null;
    previousT: number | null;
    deltaT: number | null;
    pct: number | null;
    walclT?: number | null;
    tgaB?: number | null;
    rrpB?: number | null;
  };
  momentum?: { change4w: number | null; change12w: number | null };
}

export interface SectorRow {
  ticker: string;
  name: string;
  kind: string;
  price: number | null;
  returns: Record<string, number | null>;
  alpha?: Record<string, number | null>;
  ranks?: Record<string, number>;
}

export interface SectorResponse {
  available: boolean;
  message?: string;
  period: string;
  periods: string[];
  collectedAtKst?: string;
  ageSeconds?: number;
  stale?: boolean;
  benchmark: string;
  benchmarkReturns: Record<string, number | null>;
  sectors: SectorRow[];
  assetClasses: SectorRow[];
}

export interface Holding {
  name: string;
  cusip: string;
  class: string;
  value: number | null;
  shares: number | null;
  weight: number | null;
  weightDiff: number | null;
  sharesDiff: number | null;
  action: string;
}

export interface PortfolioResponse {
  available: boolean;
  message?: string;
  error?: string | null;
  cik: string;
  institution?: { name?: string; desc?: string };
  collectedAtKst?: string;
  ageSeconds?: number;
  quarters: { filingDate: string; reportDate: string; totalValue: number; holdingCount: number }[];
  latest?: { reportDate: string; filingDate: string; totalValue: number | null };
  holdings: Holding[];
  weightHistory?: { dates: string[]; series: Record<string, number[]> };
}

export interface ConsensusRow {
  name: string;
  cusip: string;
  holders: string[];
  actions: string[];
  holderCount: number;
  /** 평가액·비중을 아는 기관만 더한 값. 하나도 모르면 null */
  totalValue: number | null;
  avgWeight: number | null;
  maxWeight: number | null;
  buyCount: number;
  sellCount: number;
}

export interface ConsensusResponse {
  available: boolean;
  participants: string[];
  participantCount: number;
  availableDates: string[];
  reportDate: string | null;
  minHolders: number;
  rows: ConsensusRow[];
}

export interface CotSummary {
  asset: string;
  available: boolean;
  category?: string;
  error?: string | null;
  date?: string;
  ncNet?: number | null;
  commNet?: number | null;
  nrNet?: number | null;
  change1w?: number | null;
  change4w?: number | null;
  change13w?: number | null;
  percentile?: number | null;
  ageDays?: number | null;
}

export interface CotAssetResponse {
  asset: string;
  available: boolean;
  message?: string;
  code?: string;
  category?: string;
  collectedAtKst?: string;
  ageSeconds?: number;
  rows: { date: string; ncNet: number; commNet: number; nrNet: number }[];
  summary?: CotSummary;
}

export interface KrxRow {
  date: string;
  futuresClose: number | null;
  changePct: number | null;
  changePctReported: number | null;
  volume: number | null;
  openInterest: number | null;
  oiChange: number | null;
  theoryPrice: number | null;
  marketBasis: number | null;
  contractName: string;
  marketPhase: string;
  cotOiIndex: number | null;
}

export interface KrxFuturesResponse {
  available: boolean;
  message?: string;
  isEstimated?: boolean;
  estimateNotice?: string;
  collectedAtKst?: string;
  ageSeconds?: number;
  stale?: boolean;
  rows: KrxRow[];
  latest?: {
    date?: string;
    futuresClose: number | null;
    changePct: number | null;
    changePctReported: number | null;
    volume: number | null;
    openInterest: number | null;
    oiChange: number | null;
    marketBasis: number | null;
    theoryPrice: number | null;
    contractName?: string;
    marketPhase?: string;
    cotOiIndex: number | null;
    basisState?: string;
    basisNote?: string;
    oiChange5dAvg: number | null;
  };
}

export interface InvestorTrendResponse {
  available: boolean;
  message?: string;
  collectedAtKst?: string;
  ageSeconds?: number;
  dataDate?: string | null;
  measure?: string;
  unit?: string;
  source?: string;
  rows: { investor: string; netToday: number; net5d: number; net20d: number; stance: string }[];
}

export interface RadarRow {
  rank: number;
  code: string;
  name: string;
  price: number | null;
  changePct: number | null;
  netAmountEok: number | null;
  source?: string;
  collectedAt?: string;
}

export interface RadarResponse {
  available: boolean;
  message?: string;
  /** 폴백 체인이 모두 실패했을 때, 소스별로 왜 못 줬는지. */
  reasons?: string[];
  warning?: string;
  market: string;
  investor: string;
  tradeType: string;
  intervalType: string;
  readMode: string;
  source?: string | null;
  sourceKind?: string | null;
  isHistorical?: boolean;
  historyDate?: string | null;
  collectedAtKst?: string;
  ageSeconds?: number;
  stale?: boolean;
  rows: RadarRow[];
}

/** 📡 외국인·기관 공통 수급 (상위 N 목록의 교집합). */
export interface RadarConsensusResponse {
  available: boolean;
  message?: string;
  reasons?: string[];
  warning?: string;
  note?: string;
  market: string;
  tradeType: string;
  intervalType: string;
  topN: number;
  foreignCount?: number;
  institutionCount?: number;
  sources?: string[];
  collectedAtKst?: string;
  ageSeconds?: number;
  stale?: boolean;
  rows: RadarConsensusRow[];
}

export interface RadarConsensusRow {
  code: string;
  name: string;
  price: number | null;
  changePct: number | null;
  foreignEok: number | null;
  institutionEok: number | null;
  /** 한쪽 금액이 없으면 null입니다(0으로 채우지 않습니다). */
  totalEok: number | null;
  foreignRank: number | null;
  institutionRank: number | null;
}

/** 💱 환율·달러인덱스 비교 차트. */
export interface FxSeriesResponse {
  available: boolean;
  message?: string;
  period: string;
  /** index = 기준일 100 · raw = 원래 단위 */
  mode: "index" | "raw";
  /** 단위가 섞인 계열을 raw로 겹쳐 그리는 중인지. 화면이 경고를 띄웁니다. */
  mixedUnits?: boolean;
  selected?: string[];
  catalog: { id: string; label: string; unit: string | null; ticker: string | null }[];
  series: FxSeries[];
  collectedAtKst?: string;
  ageSeconds?: number;
  stale?: boolean;
}

export interface FxSeries {
  id: string;
  label: string;
  unit: string | null;
  ticker: string | null;
  available: boolean;
  latest: number | null;
  latestDate: string | null;
  /** 기준일은 계열마다 다를 수 있습니다(시장별 휴일이 다릅니다). */
  baseDate: string | null;
  baseValue: number | null;
  changePct: number | null;
  points: { date: string; value: number | null }[];
}

/** 🧬 구루 스타일 프로파일. */
export interface GuruProfilesResponse {
  available: boolean;
  message?: string;
  note?: string;
  rows: GuruProfile[];
}

export interface GuruProfile {
  cik: string;
  key: string;
  name: string;
  desc: string;
  reportDate: string | null;
  totalValue: number | null;
  holdingCount: number;
  /** 1/HHI — 쏠림을 반영한 "실질" 종목 수. 종목 수만 세면 인덱스와 집중투자가 같아집니다. */
  effectiveHoldings: number | null;
  hhi: number;
  top10Weight: number;
  /** 직전 분기가 없으면 null (0이 아닙니다). */
  turnover: number | null;
  quarterCount: number;
}

/** 🤝 기관 간 유사도. */
export interface GuruSimilarityResponse {
  available: boolean;
  message?: string;
  note?: string;
  institutions: { cik: string; key: string; name: string; reportDate: string }[];
  /** 겹침 비중 (%) — 대각선은 100. */
  overlap: number[][];
  /** 코사인 유사도 (0~1) — 대각선은 1. */
  cosine: number[][];
  topPairs: { left: string; right: string; overlap: number; cosine: number }[];
}

/** 🔍 이 종목을 누가 들고 있나. */
export interface GuruHoldersResponse {
  available: boolean;
  message?: string;
  query: string;
  rows: {
    institution: string;
    cik: string;
    name: string;
    cusip: string;
    weight: number;
    value: number;
    reportDate: string;
  }[];
}

/** 🛡️ 구루 포트폴리오 위험. */
export interface GuruRiskResponse {
  available: boolean;
  message?: string;
  note?: string;
  cik: string;
  benchmark: string;
  years: number;
  institution?: Record<string, string>;
  reportDate?: string;
  collectedAtKst?: string;
  ageSeconds?: number;
  stale?: boolean;
  /** 13F에 티커가 없어 이름으로 가격을 찾습니다. 못 찾은 몫은 분석에서 빠집니다. */
  coverage?: {
    holdings: number;
    totalHoldings: number;
    weight: number;
    totalWeight: number;
    uncovered: { name: string; weight: number }[];
    uncoveredCount: number;
  };
  metrics?: {
    volatility: number | null;
    var95: number | null;
    var99: number | null;
    es95: number | null;
    es99: number | null;
    maxDrawdown: number | null;
    beta: number | null;
    trackingError: number | null;
    samples: number;
    from: string;
    to: string;
  };
  contributions?: GuruRiskContribution[];
  sectors?: { sector: string; weight: number }[];
}

export interface GuruRiskContribution {
  ticker: string;
  name: string;
  sector: string | null;
  weight: number;
  volatility: number | null;
  marginal: number | null;
  /** 기여의 합 = 포트폴리오 변동성. 개별 변동성을 그냥 더한 값이 아닙니다. */
  contribution: number | null;
  share: number | null;
}

/** 🩺 종목 스코어카드 (가격 기반 지표만). */
export interface ScorecardResponse {
  available: boolean;
  message?: string;
  symbol: string;
  name: string | null;
  sector: string | null;
  benchmark: string;
  years: number;
  universeLabel: string;
  universeSize?: number;
  /** 이 카드에 <b>없는</b> 것. 응답이 먼저 말합니다. */
  missing: string[];
  caveat: string;
  collectedAtKst?: string;
  ageSeconds?: number;
  stale?: boolean;
  metrics?: ScorecardMetric[];
  /** 5개 지표 백분위의 평균. "종합 점수"가 아닙니다. */
  priceScore?: number | null;
  scoredCount?: number;
  raw?: {
    momentum1m: number | null;
    momentum6m: number | null;
    beta: number | null;
    samples: number;
  };
}

export interface ScorecardMetric {
  label: string;
  how: string;
  value: number | null;
  unit: string;
  /** 유니버스 백분위. 비교 대상이 10개 미만이면 null입니다. */
  score: number | null;
  direction: string;
}

export interface StockUniverseResponse {
  available: boolean;
  message?: string;
  universeLabel?: string;
  rows: { ticker: string; name: string; sector: string | null }[];
}

export interface TaskSummary {
  task: string;
  speed: string | null;
  status: "ok" | "empty" | "error";
  startedAt: string | null;
  durationMs: number | null;
  detail: string | null;
}

export interface StatusResponse {
  readMode: string;
  collectorReachable: boolean;
  message?: string;
  keys?: Record<string, boolean>;
  intervals?: Record<string, number>;
  missingDatasets?: { name: string; label: string }[];
  lastRun?: {
    id?: number;
    startedAt?: string;
    finishedAt?: string;
    status?: string;
    okCount?: number;
    failCount?: number;
    detail?: string;
    pid?: number;
    groupName?: string;
  } | null;
  lastRunStatus?: string;
  taskSummary?: TaskSummary[];
  timeseriesRows?: number;
  observationRows?: number;
  snapshots?: {
    name: string;
    status: string;
    error: string | null;
    collectedAt: string | null;
    ageSeconds?: number;
    stale?: boolean;
  }[];
  radarHistoryDates?: string[];
}

export interface VerificationResult {
  name: string;
  verdict: "match" | "mismatch" | "skipped" | "error";
  label: string;
  tolerancePct: number | null;
  diffPct: number | null;
  note: string | null;
  readings: { source: string; ok: boolean; value: number | null; detail: string | null }[];
}

export interface VerificationResponse {
  available: boolean;
  message?: string;
  checkedAt?: string;
  headline?: string;
  matchCount?: number;
  mismatchCount?: number;
  errorCount?: number;
  skippedCount?: number;
  keys?: { krx: boolean; kis: boolean; toss?: boolean };
  results?: VerificationResult[];
  exitCode?: number;
}

export interface AiEngine {
  id: string;
  label: string;
  provider: string;
  model: string | null;
  description: string;
  /** 예상 응답 속도 — 추론형 모델이 왜 느린지 고르기 전에 알려 줍니다. */
  speedHint?: string;
  available: boolean;
}

export interface AiEngines {
  engines: AiEngine[];
  enabled: boolean;
  /** 엔진을 직접 골랐을 때의 대기 한도(초). */
  timeoutSeconds?: number;
  /** 자동 탐색에서 엔진 하나를 기다리는 한도(초). */
  autoAttemptSeconds?: number;
  /** 자동 탐색 전체 시간 예산(초). */
  autoBudgetSeconds?: number;
}

/** 📋 전체 대시보드 원본 데이터 (AI 분석 없음). */
export interface SnapshotText {
  text: string;
  generatedAtKst: string;
  chars: number;
  lineCount: number;
  sections: string[];
}

export interface AiResponse {
  status: boolean;
  provider?: string;
  model?: string;
  response?: string;
  error?: string | null;
  latencyMs?: number;
  pipelineStep?: string;
  failoverPath?: string[];
  translationInfo?: string;
  originalResponse?: string;
  reportType?: string;
}

export interface DiagnosticsResponse {
  available: boolean;
  message?: string;
  checkedAt?: string;
  sources?: Record<
    string,
    { ok: boolean; stage: string; message: string; sample?: unknown }
  >;
}

// ------------------------------------------------- 🔗 상관관계 · 🧭 국면
export interface SeriesRef {
  id: string;
  label: string;
  group: string;
  unit: string;
  source: string;
}

export interface CorrelationResponse {
  available: boolean;
  message?: string;
  x?: SeriesRef;
  y?: SeriesRef;
  mode: "change" | "level";
  window: number;
  /** 전체 구간 상관계수. 계산할 수 없으면 null입니다(0이 아닙니다). */
  overall: number | null;
  samples: number;
  firstDate?: string | null;
  lastDate?: string | null;
  rolling?: { date: string; value: number | null }[];
  scatter?: { date: string; x: number; y: number }[];
  notes?: string[];
}

export interface RegimeSignal {
  id: string;
  label: string;
  value: number | null;
  unit: string;
  state: string;
  reading: string;
  healthy: boolean;
}

export interface RegimeVerdict {
  code: "EXPANSION" | "LATE" | "RECOVERY" | "CONTRACTION" | "UNKNOWN";
  label: string;
  summary: string;
  growthAxis: string;
  liquidityAxis: string;
  signals: RegimeSignal[];
  missing: string[];
}

export interface RegimeResponse {
  available: boolean;
  asOf?: string | null;
  verdict?: RegimeVerdict;
  timeline?: { date: string; code: string; label: string }[];
  episodes?: { code: string; label: string; start: string; end: string; weeks: number }[];
  note?: string;
}

// ----------------------------------------------- 📉 COT 극단값 백테스트
export interface CotExtremeSummary {
  count: number;
  mean: number | null;
  median: number | null;
  winRate: number | null;
  best: number | null;
  worst: number | null;
}

export interface CotExtremeSide {
  side: string;
  rule: string;
  h4: CotExtremeSummary;
  h13: CotExtremeSummary;
  baseline4: CotExtremeSummary;
  baseline13: CotExtremeSummary;
}

export interface CotExtremesResponse {
  available: boolean;
  message?: string;
  asset: string;
  percentile: number;
  lookbackWeeks?: number;
  priceProxy?: string | null;
  priceProxyLabel?: string | null;
  proxyNotice?: string;
  priceFrom?: string;
  priceTo?: string;
  sides?: CotExtremeSide[];
  recentEvents?: {
    date: string;
    side: string;
    net: number;
    percentile: number;
    return4w: number | null;
    return13w: number | null;
  }[];
}

// ------------------------------------------------ 🆕 13F 공통 신규 매수
export interface NewBuysResponse {
  available: boolean;
  participants: string[];
  participantCount: number;
  availableDates: string[];
  reportDate?: string | null;
  minHolders: number;
  note?: string;
  rows: {
    name: string;
    cusip?: string;
    buyers: string[];
    buyerCount: number;
    totalValue: number;
    avgWeight: number;
    reportDate: string;
  }[];
}

/** 💱 원/달러 (달러 금액을 원화로 병기할 때). */
export interface UsdKrwResponse {
  available: boolean;
  /** 값이 없으면 available=false이고 rate는 없습니다(기본 환율을 지어내지 않습니다). */
  rate?: number;
  name?: string;
  lastTs?: string | null;
  source?: string | null;
  collectedAtKst?: string;
  message?: string;
}

/** GET /api/calendar/kr-holidays — 천문연 특일정보 공휴일 (시계의 KRX 휴장 판정용). */
export interface KrHolidaysResponse {
  available: boolean;
  source?: string;
  message?: string;
  years: Record<string, { announced: boolean; fetchedAt?: string; holidays: { date: string; name: string }[] }>;
}

/** GET /api/kr/fundamentals — DART 사업보고서 기반 재무 안정성·성장성 (단위 %). */
export interface KrFundamentalsCompany {
  code: string;
  available: boolean;
  name?: string | null;
  bsnsYear?: string | null;
  fsDiv?: string | null;
  fsLabel?: string;
  dartUrl?: string | null;
  capitalImpaired?: boolean;
  debtRatio?: number | null;
  roe?: number | null;
  operatingMargin?: number | null;
  revenueGrowth?: number | null;
  operatingIncomeGrowth?: number | null;
  /** 흑자전환 · 적자전환 · 적자지속 — 이때 증가율은 null */
  operatingTurn?: string | null;
  missing?: string[];
  notes?: string[];
  /** 금융위 공식 시세 (원) */
  officialClose?: number | null;
  marketCap?: number | null;
  market?: string | null;
  /** 시가총액 ÷ 직전 사업연도 순이익·자본 (배) */
  per?: number | null;
  pbr?: number | null;
  valuationNote?: string;
}

export interface KrFundamentalsResponse {
  available: boolean;
  /** 금융위 공식 시세 기준일 (시가총액·PER·PBR의 시점) */
  priceDate?: string | null;
  priceSource?: string | null;
  message?: string;
  source?: string;
  companies: KrFundamentalsCompany[];
  collectedAtKst?: string;
  ageSeconds?: number;
  stale?: boolean;
}

/** GET /api/kr/market-totals — 시장별 시가총액·거래대금 합계 (원). */
export interface KrMarketTotalsResponse {
  available: boolean;
  message?: string;
  source?: string;
  latestBasDt?: string;
  markets: { market: string; marketCap: { date: string; value: number }[]; tradingValue: { date: string; value: number }[] }[];
  collectedAtKst?: string;
  ageSeconds?: number;
  stale?: boolean;
}


/** GET /api/status/public-apis — 국내 공공 API 연결 진단 (키 값은 들어 있지 않음). */
export interface PublicApiDiagnosticsResponse {
  available: boolean;
  message?: string;
  checkedAt?: string;
  keys?: Record<string, boolean>;
  apis?: { label: string; configured: boolean; ok: boolean; detail: string | null; elapsedMs: number | null }[];
}

/** GET /api/status/issues — 수집 오류·경고 모음. text는 복사용(비밀값 가림). */
export interface StatusIssuesResponse {
  generatedAt: string;
  collectorReachable: boolean;
  lookbackHours: number;
  lastRunStatus?: string | null;
  counts: { errors: number; warnings: number; recentGroups: number; missingDatasets: number };
  current: { level: "error" | "warning"; task: string; status: string; at: string; detail: string }[];
  recent: { level: "error" | "warning"; task: string; count: number; firstAt: string; at: string; detail: string }[];
  missing: string[];
  text: string;
}

// ---------------------------------------------------------------- 투자자별 매매 (토스증권 공식)
/** 최근 N개 기록의 순매수 합. 값이 있는 날만 더하며 days가 그 일수입니다(null은 "모름"). */
export interface FlowWindow {
  sum: number | null;
  days: number;
  window: number;
}

export interface FlowRow {
  key: string;
  label: string;
  latest: number | null;
  net5: FlowWindow;
  net20: FlowWindow;
  /** +n: n일째 순매수, −n: n일째 순매도, 0: 모름·0 (기관 세부에는 없음) */
  streak?: number;
}

export interface FlowSummary {
  records: number;
  latestDate?: string;
  latestUpdatedAt?: string;
  /** 최신 기록이 오늘 — 장 종료 전까지 바뀔 수 있는 잠정치 */
  provisional?: boolean;
  investors?: FlowRow[];
  breakdown?: FlowRow[];
  foreignerHolding?: { ratePct: number; date: string; changePp: number | null; fromDate: string | null };
  series?: { date: string; foreigner: number | null; institution: number | null; individual: number | null }[];
}

export interface MarketFlowsResponse {
  available: boolean;
  message?: string;
  unit?: string;
  source?: string;
  note?: string;
  markets?: Record<string, FlowSummary>;
  collectedAtKst?: string;
  ageSeconds?: number | null;
}

export interface StockFlowsResponse {
  available: boolean;
  message?: string;
  unit?: string;
  source?: string;
  note?: string;
  stocks: (FlowSummary & { code: string; available: boolean })[];
}

export interface SpotFuturesRow {
  key: string;
  label: string;
  spotToday: number | null;
  spot5: number | null;
  spot20: number | null;
  futuresToday: number | null;
  futures5: number | null;
  futures20: number | null;
  verdictToday: string;
  verdict5: string;
  verdict20: string;
}

export interface SpotFuturesResponse {
  available: boolean;
  message?: string;
  spotDate?: string | null;
  futuresDate?: string | null;
  sameDay?: boolean;
  rows?: SpotFuturesRow[];
  spotSource?: string;
  futuresSource?: string;
  collectedAtKst?: string;
  ageSeconds?: number | null;
}
