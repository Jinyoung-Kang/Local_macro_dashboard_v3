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

describe("QA-076 이어받은 시장 수급", () => {
  // 수집 실패로 이어받은 시장(isStale)은 저장본 수집 시각이 새것이어도 그 시장 값은 예전 것입니다.
  // 화면이 그 시장을 '지금 값'처럼 보이면 안 됩니다.
  it.fails("시장 수급 패널이 이어받은 시장에 '이전 값'과 사유·받은 시각을 붙인다", async () => {
    const summary = (isStale: boolean) => ({
      records: 3, latestDate: "2026-10-16", latestUpdatedAt: "2026-10-16T05:00:00Z", provisional: false,
      investors: [], breakdown: [], fetchedAt: "2026-10-16T05:00:00Z", isStale,
      staleReason: isStale ? "KOSDAQ: HTTP 500" : null,
    });
    api.apiGetShared.mockImplementation((path: string) =>
      path.startsWith("/api/kr/investor-flows")
        ? Promise.resolve({ available: true, collectedAtKst: "2026-10-17 10:00 KST", ageSeconds: 60,
                            markets: { KOSPI: summary(false), KOSDAQ: summary(true) } })
        : new Promise(() => {}));

    const { MarketFlowsPanel } = await import("../radar/FlowPanels");
    render(<MarketFlowsPanel market="KOSDAQ" />);
    await screen.findByText(/코스닥 전체/);

    expect(screen.getByText(/이전 값/).textContent).toContain("KOSDAQ: HTTP 500");
  });

  it("새로 받은 시장에는 '이전 값'을 붙이지 않는다", async () => {
    api.apiGetShared.mockImplementation((path: string) =>
      path.startsWith("/api/kr/investor-flows")
        ? Promise.resolve({ available: true, collectedAtKst: "2026-10-17 10:00 KST", ageSeconds: 60,
                            markets: { KOSPI: { records: 3, latestDate: "2026-10-17", investors: [], breakdown: [],
                                                isStale: false } } })
        : new Promise(() => {}));

    const { MarketFlowsPanel } = await import("../radar/FlowPanels");
    render(<MarketFlowsPanel market="KOSPI" />);
    await screen.findByText(/코스피 전체/);

    expect(screen.queryByText(/이전 값/)).toBeNull();
  });
});
