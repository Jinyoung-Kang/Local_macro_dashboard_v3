/**
 * 로그인 화면 — 데모 열람(ADR 0013)이면 로그인 칸 대신 대시보드 첫 화면(/)으로 보낸다.
 * 로컬(데모 열람 꺼짐)에서는 지금처럼 로그인 칸을 보인다.
 */
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({ auth: { session: vi.fn(), login: vi.fn() } }));
vi.mock("@/lib/api", () => api);

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

import LoginPage from "../page";

beforeEach(() => {
  api.auth.session.mockReset();
  router.replace.mockReset();
});

afterEach(() => cleanup());

describe("로그인 화면", () => {
  it("데모 열람 세션이면 첫 화면(/)으로 보내고 로그인 칸을 숨긴다", async () => {
    api.auth.session.mockResolvedValue({
      authenticated: true,
      mailAvailable: false,
      readMode: "store_only",
      user: { email: null, role: "MEMBER", tier: "PRO", demo: true },
    });

    render(<LoginPage />);

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/"));
    expect(screen.queryByLabelText("비밀번호")).toBeNull();
    expect(screen.queryByRole("button", { name: "로그인" })).toBeNull();
  });

  it("로그인하지 않았으면(데모 열람 꺼짐) 지금처럼 로그인 칸을 보이고 이동하지 않는다", async () => {
    api.auth.session.mockResolvedValue({ authenticated: false, mailAvailable: true });

    render(<LoginPage />);

    expect(await screen.findByRole("link", { name: "가입하기" })).toBeTruthy();
    expect(screen.getByLabelText("이메일")).toBeTruthy();
    expect(screen.getByRole("button", { name: "로그인" })).toBeTruthy();
    expect(router.replace).not.toHaveBeenCalled();
  });
});
