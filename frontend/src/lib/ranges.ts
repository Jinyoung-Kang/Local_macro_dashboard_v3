/**
 * 차트 기간 선택의 순수 규칙 — 이미 받아 둔 시계열을 뒤에서 자릅니다.
 *
 * "전체"는 두지 않습니다. 수집기가 FRED에서 받아 두는 구간이 10년 + 90일이라
 * (collector/app/services/fred.py: period_years=10) "전체" 탭은 10년과 똑같은 그림이 되고,
 * 읽는 사람은 1982년부터의 T10Y3M을 보고 있다고 오해합니다.
 */
export const RANGES = [
  { value: "1y", label: "1년", months: 12 },
  { value: "3y", label: "3년", months: 36 },
  { value: "5y", label: "5년", months: 60 },
  { value: "10y", label: "10년", months: 120 },
] as const;

export type RangeValue = (typeof RANGES)[number]["value"];

/**
 * 선택한 기간만큼 뒤에서 잘라 냅니다. 원본은 건드리지 않습니다.
 *
 * 마지막 관측일 기준으로 자릅니다 — "오늘" 기준이면 수집이 며칠 밀렸을 때 구간이 통째로 비어 버립니다.
 * 날짜 계산은 달력 숫자로만 합니다. `new Date("YYYY-MM-DD")`(UTC 자정) → `setMonth`(현지) →
 * `toISOString`(UTC)을 섞으면 서머타임이 있는 시간대에서 기준일이 하루 밀릴 수 있습니다.
 */
export function sliceByRange<T extends { date: string }>(points: readonly T[], range: RangeValue): T[] {
  const months = RANGES.find((entry) => entry.value === range)?.months ?? null;
  if (months === null || points.length === 0) {
    return [...points];
  }
  const cutoff = monthsBefore(points[points.length - 1].date, months);
  if (cutoff === null) {
    return [...points];
  }
  return points.filter((point) => point.date >= cutoff);
}

/** 'YYYY-MM-DD'에서 n개월 전 같은 날(그 달에 없는 날은 말일). 형식이 아니면 null. */
export function monthsBefore(date: string, months: number): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(date);
  if (!match) {
    return null;
  }
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const total = year * 12 + (month - 1) - months;
  const targetYear = Math.floor(total / 12);
  const targetMonth = total - targetYear * 12 + 1;
  const lastDay = new Date(Date.UTC(targetYear, targetMonth, 0)).getUTCDate();
  const targetDay = Math.min(day, lastDay);
  return `${String(targetYear).padStart(4, "0")}-${String(targetMonth).padStart(2, "0")}-${String(targetDay).padStart(2, "0")}`;
}
