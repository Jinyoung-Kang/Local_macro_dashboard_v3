/**
 * 백엔드 응답 계약 — 공공 데이터(공휴일·DART 재무·시장 합계).
 *
 * 값이 없을 수 있는 필드는 전부 `| null`입니다. 프런트는 그 null을 그대로 "—"로 표시하고,
 * 절대 0으로 바꾸지 않습니다. 가져올 때는 `@/lib/types`(배럴)를 그대로 쓰면 됩니다.
 */
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
