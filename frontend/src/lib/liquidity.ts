/**
 * 순유동성 화면의 차트 입력 — 단위를 KPI 타일과 같게 맞춥니다.
 *
 * 저장본 단위는 조 달러(walclT)와 십억 달러(wtregenB·rrpB)로 서로 다릅니다. 화면 표기는 조·억으로
 * 통일하므로 십억 단위 계열은 10을 곱해 억으로 옮깁니다. 예전에는 차트만 셋 다 조 달러로 그려
 * (a) 타일은 "5.2 십억 달러", 차트는 "0.01T"로 단위가 달랐고 (b) ON RRP 실제 수준(약 0.005조)에서는
 * 눈금이 전부 "0.00T"였습니다. 모르는 값은 0이 아니라 null입니다.
 */
import type { LiquidityRow } from "./types";

export interface DatedValue {
  date: string;
  value: number | null;
}

export function liquidityChartSeries(rows: readonly LiquidityRow[]) {
  const billionsToEok = (value: number | null | undefined) =>
    value === null || value === undefined ? null : value * 10;
  return {
    netLiquidity: rows.map((row): DatedValue => ({ date: row.date, value: row.netLiquidityT ?? null })),
    components: {
      walcl: rows.map((row): DatedValue => ({ date: row.date, value: row.walclT ?? null })),
      tga: rows.map((row): DatedValue => ({ date: row.date, value: billionsToEok(row.wtregenB) })),
      rrp: rows.map((row): DatedValue => ({ date: row.date, value: billionsToEok(row.rrpB) })),
    },
  };
}
