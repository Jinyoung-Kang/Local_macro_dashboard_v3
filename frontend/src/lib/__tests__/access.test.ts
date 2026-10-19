import { describe, expect, it } from "vitest";
import { isDemo, isLocalOwner, isNoLogin, lockedFor, roleLabel, visibleMenus } from "../access";

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

describe("로그인 없는 로컬 실행(ADR 0016)", () => {
  it("noLogin이 정확히 true일 때만 로그인 없는 모드 — 없거나 false면(AWS·QA 스택·이전 백엔드) 로그인 모드", () => {
    expect(isNoLogin({ authenticated: true, noLogin: true })).toBe(true);
    expect(isNoLogin({ authenticated: false, noLogin: true, ownerMissing: true })).toBe(true);
    expect(isNoLogin({ authenticated: true, noLogin: false })).toBe(false);
    expect(isNoLogin({ authenticated: true })).toBe(false);
    expect(isNoLogin(undefined)).toBe(false);
    expect(isNoLogin(null)).toBe(false);
  });

  it("user.local이 true일 때만 로그인 없이 연 로컬 운영자 — 쿠키 세션·데모 열람·모르는 사용자는 아니다", () => {
    expect(isLocalOwner({ email: "o@x", role: "OWNER", tier: "PRO", local: true })).toBe(true);
    expect(isLocalOwner({ email: "o@x", role: "OWNER", tier: "PRO", local: false })).toBe(false);
    expect(isLocalOwner({ email: "o@x", role: "OWNER", tier: "PRO" })).toBe(false);
    expect(isLocalOwner({ email: null, role: "MEMBER", tier: "PRO", demo: true, local: false })).toBe(false);
    expect(isLocalOwner(undefined)).toBe(false);
    expect(isLocalOwner(null)).toBe(false);
  });

  it("로컬 운영자는 운영자 메뉴를 전부 보고, 이름은 지금처럼 '운영자'", () => {
    const local = { email: "o@x", role: "OWNER", tier: "PRO", local: true } as const;
    expect(visibleMenus(MENUS, local).map((m) => m.href)).toEqual(["/macro", "/status", "/regime", "/connections"]);
    expect(roleLabel(local)).toBe("운영자");
  });
});
