import { describe, expect, it } from "vitest";
import { roleLabel, visibleMenus } from "../access";

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
