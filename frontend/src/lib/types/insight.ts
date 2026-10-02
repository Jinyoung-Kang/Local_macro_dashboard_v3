/**
 * 백엔드 응답 계약 — 스코어카드·상관·국면.
 *
 * 값이 없을 수 있는 필드는 전부 `| null`입니다. 프런트는 그 null을 그대로 "—"로 표시하고,
 * 절대 0으로 바꾸지 않습니다. 가져올 때는 `@/lib/types`(배럴)를 그대로 쓰면 됩니다.
 */
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
