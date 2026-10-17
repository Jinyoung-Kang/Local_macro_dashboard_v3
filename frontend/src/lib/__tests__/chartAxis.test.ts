import { describe, expect, it } from "vitest";
import { distinctLabels, truncateKeepingTag, truncateToWidth, valueDomain, visualWidth } from "@/lib/chartAxis";

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

describe("구분자를 남기는 이름표 (QA-120)", () => {
  it("줄이는 쪽은 ' · ' 앞의 이름이고 구분자는 그대로 남는다", () => {
    expect(truncateKeepingTag("WELLS FARGO & CO · COM", 19)).toBe("WELLS FARGO… · COM");
    expect(truncateKeepingTag("WELLS FARGO & CO · PERP PFD CNV A", 19)).toBe("WELLS FARGO… · PERP PFD CNV A");
    expect(truncateKeepingTag("ISHARES TR · CUSIP 464287200", 19)).toBe("ISHARES TR · CUSIP 464287200");
    expect(truncateKeepingTag("SHORT · COM", 19)).toBe("SHORT · COM");
  });

  it("구분자가 없으면 truncateToWidth와 같다(예전 '앞 18자 + …'와 같은 폭)", () => {
    expect(truncateKeepingTag("BERKSHIRE HATHAWAY INC DEL", 19)).toBe("BERKSHIRE HATHAWAY…");
    expect(truncateKeepingTag("APPLE INC", 19)).toBe("APPLE INC");
  });

  it("같은 이름의 서로 다른 줄(커밋 테스트의 키·국민연금 실데이터의 ALPHABET 키)은 서로 다른 이름표가 된다", () => {
    const pairs = [
      ["WELLS FARGO & CO · COM", "WELLS FARGO & CO · PERP PFD CNV A"],
      ["ISHARES TR · CUSIP 464287200", "ISHARES TR · CUSIP 464287655"],
      ["ALPHABET INC · CAP STK CL A/ALPHABET INC/CAPSTKCLA", "ALPHABET INC · CAP STK CL C/ALPHABET INC/CAPSTKCLC"],
      ["ALPHABET INC CLASS A COMMON · CAP STK CL A/ALPHABET INC", "ALPHABET INC CLASS A COMMON · CAP STK CL C/ALPHABET INC"],
    ];
    for (const pair of pairs) {
      const [first, second] = pair.map((key) => truncateKeepingTag(key, 19));
      expect(first).not.toBe(second);
    }
    const keys = pairs.flat();
    const labels = distinctLabels(keys, 19);
    expect(new Set(labels).size).toBe(keys.length);
    expect(labels[0]).toBe("WELLS FARGO… · COM");
  });

  it("이름 앞부분이 같아 줄인 이름표가 겹치면 그 둘은 줄이지 않은 키로 둔다 — 키가 다르면 이름표도 다르다", () => {
    const keys = ["VANGUARD INDEX FUNDS GROWTH", "VANGUARD INDEX FUNDS VALUE", "APPLE INC"];
    expect(keys.slice(0, 2).map((key) => truncateKeepingTag(key, 19))).toEqual(["VANGUARD INDEX FUN…", "VANGUARD INDEX FUN…"]);
    expect(distinctLabels(keys, 19)).toEqual(["VANGUARD INDEX FUNDS GROWTH", "VANGUARD INDEX FUNDS VALUE", "APPLE INC"]);
  });
});
