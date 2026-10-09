import { describe, expect, it } from "vitest";
import { regimeInputCells } from "../regime";

describe("국면 판정 기록의 근거 표기", () => {
  it("단위를 다른 화면과 같게 적고, 기준일은 월-일로 줄인다", () => {
    const cells = regimeInputCells({
      curve10y3m: { value: 0.42, asOf: "2026-10-08" },
      nfci: { value: -0.48, asOf: "2026-10-02" },
      hyOas: { value: 2.85, asOf: "2026-10-08" },
      liquidity4w: { value: -0.0254, asOf: "2026-10-08" },
    });
    expect(cells.map((c) => [c.label, c.value, c.asOf])).toEqual([
      ["10Y-3M", "+0.42%p", "10-08"],
      ["NFCI", "-0.48", "10-02"],
      ["하이일드", "2.85%p", "10-08"],
      ["순유동성 4주", "-254.0억 달러", "10-08"],
    ]);
  });

  it("값을 모르면 0이 아니라 —", () => {
    const cells = regimeInputCells({ nfci: { value: null, asOf: null } });
    expect(cells.every((c) => c.value === "—")).toBe(true);
    expect(cells[1].asOf).toBe("—");
  });
});
