import { describe, expect, it } from "vitest";
import { monthsBefore, sliceByRange } from "@/lib/ranges";

describe("monthsBefore", () => {
  it("달력 숫자로만 계산한다 — 시간대·서머타임과 무관", () => {
    expect(monthsBefore("2026-03-31", 1)).toBe("2026-02-28");   // 그 달에 없는 날은 말일
    expect(monthsBefore("2024-03-31", 1)).toBe("2024-02-29");
    expect(monthsBefore("2026-01-15", 12)).toBe("2025-01-15");
    expect(monthsBefore("2026-01-15", 13)).toBe("2024-12-15");
    expect(monthsBefore("2026-10-02", 120)).toBe("2016-10-02");
    expect(monthsBefore("not a date", 1)).toBeNull();
  });
});

describe("sliceByRange", () => {
  const points = ["2016-09-30", "2016-10-02", "2020-01-01", "2026-10-02"].map((date) => ({ date }));

  it("마지막 관측일 기준으로 뒤에서 자르고 원본은 두지 않는다", () => {
    expect(sliceByRange(points, "10y").map((p) => p.date)).toEqual(["2016-10-02", "2020-01-01", "2026-10-02"]);
    expect(sliceByRange(points, "1y").map((p) => p.date)).toEqual(["2026-10-02"]);
    expect(points).toHaveLength(4);
  });

  it("빈 목록은 빈 목록", () => {
    expect(sliceByRange([], "1y")).toEqual([]);
  });
});
