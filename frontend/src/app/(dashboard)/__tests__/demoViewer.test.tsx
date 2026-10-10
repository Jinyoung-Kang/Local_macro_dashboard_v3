/**
 * 데모 열람(ADR 0013) — 읽기 전용 방문자에게 쓰기 버튼을 막고 "데모에서는 조회만 가능합니다"를 보인다.
 * 백엔드도 GET 밖은 403이지만, 눌러 봐야 403만 받는 버튼은 처음부터 막습니다.
 * 계정 사용자(레이아웃 밖 = 사용자 모름 포함)는 지금과 같습니다.
 */
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SessionUserProvider } from "@/hooks/useSessionUser";
import type { SessionUser } from "@/lib/types";

const api = vi.hoisted(() => {
  class UnauthorizedError extends Error {}
  return {
    apiGet: vi.fn(),
    apiPost: vi.fn(),
    apiDelete: vi.fn(),
    apiGetShared: vi.fn(),
    UnauthorizedError,
    auth: { changePassword: vi.fn(), logoutOthers: vi.fn(), deleteAccount: vi.fn() },
  };
});
vi.mock("@/lib/api", () => api);

// 매 렌더마다 새 함수를 주면 useApi가 다시 읽기를 반복합니다 — 같은 값을 돌려줍니다.
const refresh = vi.hoisted(() => ({ token: 0, reloadAll: () => {}, notifyUnauthorized: () => {} }));
vi.mock("@/hooks/useRefreshSignal", () => ({ useRefreshSignal: () => refresh }));

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => "/" }));

import AccountPage from "../account/page";
import AiReportPage from "../ai/report/page";
import AlertsPage from "../alerts/page";

const DEMO: SessionUser = { email: null, role: "MEMBER", tier: "PRO", demo: true };
const MEMBER: SessionUser = { email: "member@test.local", role: "MEMBER", tier: "PRO" };

const READS: Record<string, unknown> = {
  "/api/ai/engines": {
    enabled: true,
    engines: [{ id: "auto", label: "자동", provider: "auto", model: null, description: "", available: true }],
  },
  "/api/ai/report-types": { types: ["종합 매크로 브리핑"] },
  "/api/snapshot/text": { text: "원본", generatedAtKst: "2026-10-11 10:00 KST", chars: 2 },
  "/api/alerts/inbox?limit=50": { items: [], unread: 0 },
  "/api/alerts/rules": { available: true, limit: 20, rules: [] },
  "/api/sec13f/institutions": { institutions: [] },
};

function renderAs(user: SessionUser | undefined, page: React.ReactNode) {
  return render(<SessionUserProvider user={user}>{page}</SessionUserProvider>);
}

beforeEach(() => {
  api.apiPost.mockReset();
  api.apiDelete.mockReset();
  api.apiGetShared.mockReset();
  api.apiGetShared.mockImplementation((path: string) =>
    path in READS ? Promise.resolve(READS[path]) : new Promise(() => {}),
  );
  router.replace.mockReset();
});

afterEach(() => cleanup());

describe("데모 열람 — 쓰기 버튼", () => {
  it("AI 리포트: 생성 버튼이 막히고 안내를 보인다(원본 데이터 보기는 그대로)", async () => {
    renderAs(DEMO, <AiReportPage />);

    const generate = (await screen.findByRole("button", { name: "🚀 리포트 생성" })) as HTMLButtonElement;
    await waitFor(() => expect(screen.getByText("데모에서는 조회만 가능합니다")).toBeTruthy());
    expect(generate.disabled).toBe(true);
    fireEvent.click(generate);
    expect(api.apiPost).not.toHaveBeenCalled();
    expect((screen.getByRole("button", { name: "AI에 전달되는 원본 데이터 보기" }) as HTMLButtonElement).disabled)
      .toBe(false);
  });

  it("AI 리포트: 계정 회원은 지금처럼 생성 버튼을 쓴다", async () => {
    renderAs(MEMBER, <AiReportPage />);

    const generate = (await screen.findByRole("button", { name: "🚀 리포트 생성" })) as HTMLButtonElement;
    await waitFor(() => expect(generate.disabled).toBe(false));
    expect(screen.queryByText("데모에서는 조회만 가능합니다")).toBeNull();
  });

  it("알림: 규칙 만들기가 막히고 안내를 보인다", async () => {
    renderAs(DEMO, <AlertsPage />);

    const create = (await screen.findByRole("button", { name: "규칙 만들기" })) as HTMLButtonElement;
    expect(create.disabled).toBe(true);
    expect(screen.getByText("데모에서는 조회만 가능합니다")).toBeTruthy();
    fireEvent.click(create);
    expect(api.apiPost).not.toHaveBeenCalled();
  });

  it("알림: 계정 회원은 지금처럼 규칙을 만들 수 있다", async () => {
    renderAs(MEMBER, <AlertsPage />);

    const create = (await screen.findByRole("button", { name: "규칙 만들기" })) as HTMLButtonElement;
    expect(create.disabled).toBe(false);
    expect(screen.queryByText("데모에서는 조회만 가능합니다")).toBeNull();
  });
});

describe("데모 열람 — 내 계정", () => {
  it("계정이 없으므로 첫 화면(/)으로 보내고 계정 API를 부르지 않는다", async () => {
    renderAs(DEMO, <AccountPage />);

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/"));
    expect(screen.queryByText("비밀번호 변경")).toBeNull();
    expect(api.apiGetShared).not.toHaveBeenCalled();
  });
});
