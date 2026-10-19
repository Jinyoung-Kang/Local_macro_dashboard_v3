/**
 * 사이드바 아래쪽 계정 영역 — 데모 열람(ADR 0013)이면 로그아웃·내 계정 링크 대신 "데모 열람" 표시.
 * 로그인 없이 연 로컬 운영자(ADR 0016)면 로그아웃 대신 "로컬 · 로그인 없음"을 내 계정 링크로 보인다.
 */
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({ auth: { logout: vi.fn() } }));
vi.mock("@/lib/api", () => api);

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));
vi.mock("next/navigation", () => ({
  usePathname: () => "/macro",
  useRouter: () => router,
}));

vi.mock("@/hooks/useRefreshSignal", () => ({
  useRefreshSignal: () => ({ requestRefresh: vi.fn(), collecting: false, message: null }),
}));

import { Sidebar } from "../Sidebar";

beforeEach(() => {
  api.auth.logout.mockReset();
  router.push.mockReset();
});

afterEach(() => cleanup());

describe("사이드바 계정 영역", () => {
  it("데모 열람이면 로그아웃·내 계정 링크가 없고 '데모 열람'과 조회 전용 안내를 보인다", () => {
    render(<Sidebar readMode="store_only" user={{ email: null, role: "MEMBER", tier: "PRO", demo: true }} unread={0} />);

    expect(screen.queryByRole("button", { name: "로그아웃" })).toBeNull();
    expect(document.querySelector('a[href="/account"]')).toBeNull();
    expect(screen.getByText("데모 열람")).toBeTruthy();
    expect(screen.getByText(/데모에서는 조회만 가능합니다/)).toBeTruthy();
    // 운영 메뉴는 보이지 않고, 유료 메뉴(AI)는 잠금 표시 없이 보입니다.
    expect(screen.queryByRole("link", { name: "데이터 저장소 상태" })).toBeNull();
    expect(screen.getByRole("link", { name: "AI 종합 데이터 분석" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: /수동 새로고침/ })).toBeNull();
  });

  it("계정으로 로그인한 회원은 지금처럼 이메일·등급 링크(내 계정)와 로그아웃을 보인다", () => {
    render(<Sidebar readMode="auto" user={{ email: "member@test.local", role: "MEMBER", tier: "FREE" }} unread={0} />);

    expect(screen.getByRole("button", { name: "로그아웃" })).toBeTruthy();
    const account = document.querySelector('a[href="/account"]');
    expect(account?.textContent).toContain("member@test.local");
    expect(account?.textContent).toContain("무료 회원");
    expect(screen.queryByText("데모 열람")).toBeNull();
  });

  it("로그인 없이 연 로컬 운영자는 로그아웃 없이 '로컬 · 로그인 없음'·역할을 내 계정 링크로 보이고, 운영 메뉴를 본다", () => {
    render(<Sidebar readMode="auto" user={{ email: "owner@test.local", role: "OWNER", tier: "PRO", local: true }} unread={0} />);

    expect(screen.queryByRole("button", { name: "로그아웃" })).toBeNull();
    const account = document.querySelector('a[href="/account"]');
    expect(account?.textContent).toContain("로컬 · 로그인 없음");
    expect(account?.textContent).toContain("운영자");
    expect(screen.getByRole("link", { name: "데이터 저장소 상태" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "사용자 관리" })).toBeTruthy();
    expect(screen.getAllByRole("button", { name: /수동 새로고침/ }).length).toBeGreaterThan(0);
  });

  it("쿠키 세션으로 로그인한 운영자(local 아님)는 지금처럼 로그아웃을 보인다", () => {
    render(<Sidebar readMode="auto" user={{ email: "owner@test.local", role: "OWNER", tier: "PRO", local: false }} unread={0} />);

    expect(screen.getByRole("button", { name: "로그아웃" })).toBeTruthy();
    expect(document.querySelector('a[href="/account"]')?.textContent).toContain("owner@test.local");
    expect(screen.queryByText(/로컬 · 로그인 없음/)).toBeNull();
  });

  it("데모 열람 표시가 앞선다 — local이 함께 와도 '데모 열람'만 보이고 로그아웃·내 계정 링크가 없다", () => {
    render(<Sidebar readMode="store_only" user={{ email: null, role: "MEMBER", tier: "PRO", demo: true, local: true }} unread={0} />);

    expect(screen.getByText("데모 열람")).toBeTruthy();
    expect(screen.queryByText(/로컬 · 로그인 없음/)).toBeNull();
    expect(screen.queryByRole("button", { name: "로그아웃" })).toBeNull();
    expect(document.querySelector('a[href="/account"]')).toBeNull();
  });
});
