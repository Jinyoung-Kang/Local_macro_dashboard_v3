/**
 * 막대 차트 데이터 — 값을 모르는 항목을 0으로 그리지 않습니다.
 *
 * 수급 레이더는 토스 대체 경로에서 가격을 모르면 금액이 null입니다. 예전에는 `?? 0`으로
 * "순매수 0억" 막대를 그려, 모르는 값을 사실처럼 보여 줬습니다(표는 "—"로 올바름).
 */
import { describe, expect, it } from "vitest";
import { knownBars } from "@/lib/chartData";

type Row = { name: string; amount: number | null | undefined };
const rows: Row[] = [
  { name: "삼성전자", amount: 1234.5 },
  { name: "가격모름", amount: null },
  { name: "SK하이닉스", amount: -12 },
  { name: "필드없음", amount: undefined },
  { name: "정말 0", amount: 0 },
];

describe("knownBars", () => {
  it("값을 모르는 항목은 막대에서 빼고 개수를 알려 준다", () => {
    const { bars, omitted } = knownBars(rows, (r) => r.name, (r) => r.amount);
    expect(bars.map((b) => b.name)).toEqual(["삼성전자", "SK하이닉스", "정말 0"]);
    expect(omitted).toBe(2);
  });

  it("실제 0과 음수는 그대로 그린다", () => {
    const { bars } = knownBars(rows, (r) => r.name, (r) => r.amount);
    expect(bars).toContainEqual({ name: "정말 0", value: 0 });
    expect(bars).toContainEqual({ name: "SK하이닉스", value: -12 });
  });

  it("순서를 바꾸지 않는다(서버가 매긴 순위 그대로)", () => {
    const { bars } = knownBars(rows, (r) => r.name, (r) => r.amount);
    expect(bars[0].name).toBe("삼성전자");
  });
});
