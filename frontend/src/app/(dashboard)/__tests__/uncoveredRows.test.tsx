/**
 * 13F 위험 분석의 미커버 목록이 이름이 같은 여러 줄을 서로 다른 행으로 그린다(QA-135).
 *
 * 백엔드는 보유를 줄(증권)마다 미커버로 넘기고 이름은 공시 이름 그대로입니다 — 같은 회사의 보통주·우선주·사채가 같은 이름으로
 * 여러 줄 올 수 있습니다(QA-127). 이름을 React 키로 쓰면 중복 키 경고가 나고, 다시 그릴 때 줄이 빠지거나 겹칠 수 있습니다.
 */
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => {
  class UnauthorizedError extends Error {}
  return { apiGet: vi.fn(), apiPost: vi.fn(), apiGetShared: vi.fn(), UnauthorizedError };
});
vi.mock("@/lib/api", () => api);
const signal = vi.hoisted(() => ({ token: 0, reloadAll: () => {}, notifyUnauthorized: () => {} }));
vi.mock("@/hooks/useRefreshSignal", () => ({ useRefreshSignal: () => signal }));

import GuruPage from "../style/page";

afterEach(() => cleanup());

const RISK = {
  available: false, message: "가격을 찾을 수 있는 종목이 없습니다.", cik: "0001067983", benchmark: "SPY", years: 1,
  coverage: {
    holdings: 0, totalHoldings: 2, weight: 0, totalWeight: 50, uncoveredCount: 2,
    uncovered: [
      { name: "WELLS FARGO CO NEW", weight: 30, reason: "보통주가 아님(우선주·워런트 등)" },
      { name: "WELLS FARGO CO NEW", weight: 20, reason: "채권" },
    ],
  },
};

describe("QA-135 13F 위험 분석 — 미커버 목록", () => {
  // 수정 전에는 key={row.name}이라 같은 이름의 두 줄이 같은 키를 받았다.
  it("이름이 같은 두 줄을 중복 키 경고 없이 모두 그린다", async () => {
    const errors = vi.spyOn(console, "error").mockImplementation(() => {});
    api.apiGetShared.mockImplementation((path: string) =>
      path.startsWith("/api/guru/risk") ? Promise.resolve(RISK) : new Promise(() => {}));

    render(<GuruPage />);

    await screen.findByText("미커버 2종목 보기");
    expect(screen.getAllByText(/WELLS FARGO CO NEW/)).toHaveLength(2);
    expect(errors.mock.calls.map(([message]) => String(message)).filter((message) => message.includes("same key")))
      .toEqual([]);
  });
});
