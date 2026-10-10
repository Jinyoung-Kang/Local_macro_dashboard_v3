import { describe, expect, it } from "vitest";
import { isDemo, lockedFor, roleLabel, visibleMenus } from "../access";

const MENUS = [{ href: "/macro" }, { href: "/status" }, { href: "/regime" }, { href: "/connections" }];

describe("메뉴 고르기", () => {
  it("운영자는 전부, 회원은 운영 메뉴(저장소 상태·연결 테스트)를 보지 않는다", () => {
    expect(visibleMenus(MENUS, { email: "o@x", role: "OWNER", tier: "PRO" }).map((m) => m.href))
      .toEqual(["/macro", "/status", "/regime", "/connections"]);
    expect(visibleMenus(MENUS, { email: "m@x", role: "MEMBER", tier: "PRO" }).map((m) => m.href))
      .toEqual(["/macro", "/regime"]);
  });

  it("사용자를 모르면 운영 메뉴를 숨긴다", () => {
    expect(visibleMenus(MENUS, undefined).map((m) => m.href)).toEqual(["/macro", "/regime"]);
  });

  it("역할·등급 이름", () => {
    expect(roleLabel({ email: "o@x", role: "OWNER", tier: "FREE" })).toBe("운영자");
    expect(roleLabel({ email: "m@x", role: "MEMBER", tier: "PRO" })).toBe("유료 회원");
    expect(roleLabel({ email: "m@x", role: "MEMBER", tier: "FREE" })).toBe("무료 회원");
  });
});

describe("유료 메뉴 잠금 표시", () => {
  it("무료 회원에게만 잠금", () => {
    expect(lockedFor({ email: "m@x", role: "MEMBER", tier: "FREE" }, "/ai/report")).toBe(true);
    expect(lockedFor({ email: "m@x", role: "MEMBER", tier: "PRO" }, "/ai/report")).toBe(false);
    expect(lockedFor({ email: "o@x", role: "OWNER", tier: "FREE" }, "/ai/report")).toBe(false);
    expect(lockedFor({ email: "m@x", role: "MEMBER", tier: "FREE" }, "/macro")).toBe(false);
  });
});

describe("데모 열람(ADR 0013)", () => {
  const demo = { email: null, role: "MEMBER", tier: "PRO", demo: true } as const;

  it("데모 열람이면 '데모 열람', 운영 메뉴는 숨기고 유료 메뉴는 잠그지 않는다", () => {
    expect(isDemo(demo)).toBe(true);
    expect(roleLabel(demo)).toBe("데모 열람");
    expect(visibleMenus(MENUS, demo).map((m) => m.href)).toEqual(["/macro", "/regime"]);
    expect(lockedFor(demo, "/ai/report")).toBe(false);
  });

  it("계정 사용자·모르는 사용자는 데모가 아니다", () => {
    expect(isDemo({ email: "m@x", role: "MEMBER", tier: "PRO" })).toBe(false);
    expect(isDemo({ email: "m@x", role: "MEMBER", tier: "PRO", demo: false })).toBe(false);
    expect(isDemo(undefined)).toBe(false);
    expect(isDemo(null)).toBe(false);
  });
});
