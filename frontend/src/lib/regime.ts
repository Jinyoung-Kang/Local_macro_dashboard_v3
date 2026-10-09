/**
 * 🧭 국면 판정 기록의 표기 — React를 모르는 순수 함수(ADR 0004).
 *
 * 판정 근거 네 개를 한 줄로 적습니다. 단위는 화면의 다른 곳과 같게: 금리차·하이일드는 %p,
 * NFCI는 단위 없는 지수, 순유동성 변화는 조·억 달러(PRINCIPLES "한국식 단위").
 * 값을 모르는 입력은 "—"로 둡니다 — 0으로 채우지 않습니다.
 */
import { EMPTY, formatSigned, formatTrillionDelta } from "./format";
import type { RegimeLogEntry, RegimeLogInput } from "./types";

const ORDER = [
  { key: "curve10y3m", label: "10Y-3M", format: (v: number) => `${formatSigned(v, 2)}%p` },
  { key: "nfci", label: "NFCI", format: (v: number) => formatSigned(v, 2) },
  { key: "hyOas", label: "하이일드", format: (v: number) => `${formatSigned(v, 2).replace(/^\+/, "")}%p` },
  { key: "liquidity4w", label: "순유동성 4주", format: (v: number) => formatTrillionDelta(v) },
] as const;

/** "2026-10-08" → "10-08". 형식이 다르면 그대로. */
function shortDate(value: string | null | undefined): string {
  return value && /^\d{4}-\d{2}-\d{2}/.test(value) ? value.slice(5, 10) : (value ?? EMPTY);
}

/** 판정 근거를 표시 순서대로: [{label, value, asOf}]. */
export function regimeInputCells(inputs: RegimeLogEntry["inputs"]) {
  return ORDER.map(({ key, label, format }) => {
    const input: RegimeLogInput | undefined = inputs?.[key];
    const value = input?.value;
    return {
      key,
      label,
      value: value === null || value === undefined || !Number.isFinite(value) ? EMPTY : format(value),
      asOf: input?.asOf ? shortDate(input.asOf) : EMPTY,
    };
  });
}
