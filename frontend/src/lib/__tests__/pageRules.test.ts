/**
 * 화면 안에 있던 데이터 가공 규칙 — React 없이 테스트합니다(ADR 0004 규칙 3).
 */
import { describe, expect, it } from "vitest";
import { correlationStrength } from "@/lib/correlation";
import { streakText } from "@/lib/flows";
import { liquidityChartSeries } from "@/lib/liquidity";
import { sortBySourceOrder, sourceLabel, splitFundamentals } from "@/lib/radar";
import { spreadSummary } from "@/lib/spread";
import type { KrFundamentalsCompany, LiquidityRow } from "@/lib/types";

describe("radar", () => {
  it("소스는 폴백 체인 순서로, 모르는 소스는 뒤로", () => {
    const rows = sortBySourceOrder({ pykrx: { ok: true }, kis: { ok: false }, mystery: { ok: true }, daum: { ok: true } });
    expect(rows.map((r) => r.name)).toEqual(["kis", "daum", "pykrx", "mystery"]);
    expect(sourceLabel("toss")).toBe("토스증권 (공식)");
    expect(sourceLabel("mystery")).toBe("MYSTERY");
  });

  it("재무 패널은 자료가 있는 종목만 표에 두고 나머지를 센다", () => {
    const companies = [
      { code: "005930", available: true } as KrFundamentalsCompany,
      { code: "000660", available: false, marketCap: 1.0 } as KrFundamentalsCompany,
    ];
    const { rows, empty, covered } = splitFundamentals(["005930", "000660", "035420"], companies);
    expect(rows.map((r) => r.code)).toEqual(["005930", "000660"]);
    expect(empty.map((r) => r.code)).toEqual(["035420"]);
    expect(covered).toBe(1);
  });
});

describe("liquidity", () => {
  it("십억 달러 계열은 억으로(×10), 모르는 값은 null", () => {
    const rows = [{ date: "2026-09-30", netLiquidityT: 6.1, walclT: 6.6, wtregenB: 877, rrpB: null }] as unknown as LiquidityRow[];
    const { netLiquidity, components } = liquidityChartSeries(rows);
    expect(netLiquidity).toEqual([{ date: "2026-09-30", value: 6.1 }]);
    expect(components.walcl[0].value).toBe(6.6);
    expect(components.tga[0].value).toBe(8770);
    expect(components.rrp[0].value).toBeNull();
  });
});

describe("spread", () => {
  it("한쪽을 모르면 변화도 모르고, 역전은 아는 값에서만", () => {
    const base = { longId: "DGS10", shortId: "DGS2", points: [] };
    expect(spreadSummary({ ...base, latest: -0.2, previous: 0.1 } as never)).toMatchObject({
      change: -0.30000000000000004, inverted: true, pair: "10Y−2Y",
    });
    expect(spreadSummary({ ...base, latest: null, previous: 0.1 } as never)).toMatchObject({ change: null, inverted: false });
    expect(spreadSummary(undefined).subtitle).toContain("FRED");
  });
});

describe("correlation", () => {
  it("세기와 방향", () => {
    expect(correlationStrength(null)).toBe("계산할 수 없습니다.");
    expect(correlationStrength(0.1)).toBe("거의 무관합니다.");
    expect(correlationStrength(-0.35)).toBe("약하게 반대 방향으로 움직였습니다.");
    expect(correlationStrength(0.5)).toBe("어느 정도 같은 방향으로 움직였습니다.");
    expect(correlationStrength(0.9)).toBe("뚜렷하게 같은 방향으로 움직였습니다.");
  });
});

describe("flows", () => {
  it("연속 일수 표기 — 기록 전부가 같은 방향이면 +", () => {
    expect(streakText(undefined, 20)).toBe("—");
    expect(streakText(0, 20)).toBe("—");
    expect(streakText(3, 20)).toBe("3일 순매수");
    expect(streakText(-20, 20)).toBe("20일+ 순매도");
  });
});
