/**
 * 숫자 서식 — PRINCIPLES "없는 숫자를 만들지 않는다"가 실제로 글자가 되는 곳.
 *
 * 그동안 시각(formatKst)만 검사하고 숫자 서식은 테스트가 없었습니다. 경계에서 틀린 네 가지를
 * 고정합니다: Infinity가 "∞"로, 9,999.95억이 "10,000.0억"으로, 0.4원이 "+0 원"으로,
 * NaN 경과 시간이 "NaN일 전"으로 찍히던 것.
 */
import { describe, expect, it } from "vitest";
import {
  deltaColor, formatAge, formatBillionUsd, formatKrw, formatNumber, formatPercent, formatShares,
  formatSigned, formatSignedKrw, formatTrillionDelta, formatTrillionUsd, formatUsd, formatUsdWithKrw,
} from "@/lib/format";

describe("값이 없으면 —", () => {
  it("null·undefined·NaN·Infinity 모두 —", () => {
    for (const value of [null, undefined, NaN, Infinity, -Infinity]) {
      expect(formatNumber(value)).toBe("—");
      expect(formatSigned(value)).toBe("—");
      expect(formatPercent(value)).toBe("—");
      expect(formatUsd(value)).toBe("—");
      expect(formatShares(value)).toBe("—");
      expect(formatAge(value)).toBe("—");
      expect(deltaColor(value)).toBe("text-muted");
    }
  });
});

describe("부호와 색은 보이는 숫자 기준", () => {
  it("-0.004는 2자리에서 0.00이고 중립색", () => {
    expect(formatNumber(-0.004)).toBe("0.00");
    expect(formatSigned(-0.004)).toBe("0.00");
    expect(deltaColor(-0.004)).toBe("text-body");
    expect(deltaColor(-0.004, 3)).toBe("text-down");
    expect(formatSigned(0.006)).toBe("+0.01");
  });

  it("원화·주식 수의 +는 0으로 찍힐 때 붙지 않는다", () => {
    expect(formatSignedKrw(0.4)).toBe("0 원");
    expect(formatSignedKrw(1234e8)).toBe("+1,234억 원");
    expect(formatShares(0.4)).toBe("0 주");
    expect(formatShares(292_000)).toBe("+29.2만 주");
    expect(formatShares(-1250)).toBe("-1,250 주");
  });
});

describe("조·억·만 단위", () => {
  it("단위와 자릿수", () => {
    expect(formatUsd(4.47e10)).toBe("447.0억 달러");
    expect(formatUsd(1.2e11)).toBe("1,200억 달러");
    expect(formatKrw(8_607_639e7)).toBe("86.076조 원");     // 100조 미만: 소수 3자리
    expect(formatKrw(8_607_639e8)).toBe("860.8조 원");      // 100조 이상: 1자리
    expect(formatKrw(8_607_639e9)).toBe("8,608조 원");      // 1,000조 이상: 정수
    expect(formatUsd(123_456)).toBe("12만 달러");
    expect(formatUsd(999)).toBe("999 달러");
  });

  it("반올림이 다음 단위에 닿으면 단위를 올린다", () => {
    expect(formatUsd(9.9995e11)).toBe("1.000조 달러");    // 예전: "10,000.0억 달러"
    expect(formatUsd(99_995_000)).toBe("1.0억 달러");       // 예전: "10,000만 달러"
    expect(formatUsd(9_999.6)).toBe("1만 달러");
  });

  it("조·십억 단위로 들어온 값", () => {
    expect(formatTrillionUsd(0.012)).toBe("120.0억 달러");
    expect(formatBillionUsd(877)).toBe("8,770억 달러");
    expect(formatTrillionDelta(-0.0005)).toBe("-5.0억 달러");
    expect(formatTrillionDelta(0)).toBe("0 달러");
  });

  it("환율을 모르면 달러만", () => {
    expect(formatUsdWithKrw(4.47e10, 1382)).toBe("447.0억 달러 (약 61.775조 원)");
    expect(formatUsdWithKrw(4.47e10, null)).toBe("447.0억 달러");
    expect(formatUsdWithKrw(4.47e10, 0)).toBe("447.0억 달러");
  });
});

describe("경과 시간", () => {
  it("초·분·시간·일, 음수는 0으로", () => {
    expect(formatAge(42)).toBe("42초 전");
    expect(formatAge(180)).toBe("3분 전");
    expect(formatAge(7200)).toBe("2시간 전");
    expect(formatAge(172_800)).toBe("2일 전");
    expect(formatAge(-5)).toBe("0초 전");
  });
});
