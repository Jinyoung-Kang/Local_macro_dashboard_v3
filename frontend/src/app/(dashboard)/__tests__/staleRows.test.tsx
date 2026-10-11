/**
 * 수집 실패로 이어받은 이전 값(isStale) 행을 '지금 값'처럼 보이지 않는다(QA-065).
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

import SectorPage from "../sector/page";

afterEach(() => cleanup());

describe("QA-065 이어받은 업종 행", () => {
  it("업종 순위 표가 이전 값 행에 '이전 값'을 붙인다", async () => {
    const row = (ticker: string, isStale: boolean) => ({
      ticker, name: ticker === "XLC" ? "통신서비스" : "정보기술", kind: "공격 / 성장", price: 100,
      returns: { "1M": 1.5 }, ranks: { "1M": 1 }, isStale, staleReason: isStale ? "429" : null,
    });
    api.apiGetShared.mockImplementation((path: string) =>
      path.startsWith("/api/sector/rotation")
        ? Promise.resolve({ available: true, periods: ["1M"], period: "1M",
                            sectors: [row("XLK", false), row("XLC", true)], assetClasses: [] })
        : new Promise(() => {}));

    render(<SectorPage />);
    await screen.findByText("통신서비스");

    expect(screen.getAllByText(/이전 값/)).toHaveLength(1);
  });
});
