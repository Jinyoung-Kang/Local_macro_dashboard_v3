import { describe, expect, it } from "vitest";
import { truncateToWidth, valueDomain, visualWidth } from "@/lib/chartAxis";

describe("valueDomain", () => {
  it("값이 없으면 auto", () => {
    expect(valueDomain([{ value: null }], false)).toEqual(["auto", "auto"]);
  });

  it("음수가 될 수 없는 계열은 축을 0 아래로 내리지 않는다", () => {
    const [lower, upper] = valueDomain([{ value: 0.004 }, { value: 0.006 }], false) as [number, number];
    expect(lower).toBeGreaterThanOrEqual(0);
    expect(upper).toBeGreaterThan(0.006);
  });

  it("부호가 중요한 계열만 0을 포함한다", () => {
    const [lower] = valueDomain([{ value: 1.2 }, { value: 1.5 }], true) as [number, number];
    expect(lower).toBeLessThanOrEqual(0);
    const [lowerNoZero] = valueDomain([{ value: 1.2 }, { value: 1.5 }], false) as [number, number];
    expect(lowerNoZero).toBeGreaterThan(0);
  });

  it("값이 전부 같아도 범위가 0이 되지 않는다", () => {
    const [lower, upper] = valueDomain([{ value: 5 }, { value: 5 }], false) as [number, number];
    expect(upper).toBeGreaterThan(lower);
  });
});

describe("라벨 폭", () => {
  it("한글은 라틴 문자의 두 배로 센다", () => {
    expect(visualWidth("abc")).toBe(3);
    expect(visualWidth("반도체")).toBe(6);
    expect(visualWidth("KODEX 레버리지")).toBe(6 + 8);
  });

  it("폭을 넘으면 … 로 줄인다", () => {
    expect(truncateToWidth("abc", 10)).toBe("abc");
    expect(truncateToWidth("TIGER 미국필라델피아반도체나스닥", 12)).toBe("TIGER 미국…");
  });
});
