/**
 * 수집 실패로 이어받은 이전 값(isStale) 행을 '지금 값'처럼 보이지 않는다(QA-065·QA-076·QA-116).
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
  it("시장 수급 패널이 이어받은 시장에 '이전 값'과 사유·받은 시각을 붙인다", async () => {
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

describe("QA-116 이어받은 코스피 현물", () => {
  // 현물·선물 카드도 같은 토스 시장 저장본의 KOSPI 기록을 씁니다. KOSPI만 실패해 이어받으면 카드의 수집 시각은
  // 방금 받은 코스닥의 것이라, 현물 값이 예전 것이라는 표시가 따로 있어야 합니다.
  const body = (spotIsStale: boolean) => ({
    available: true, spotDate: "2026-10-16", futuresDate: "2026-10-16", sameDay: true, rows: [],
    collectedAtKst: "2026-10-18 10:00 KST", ageSeconds: 60,
    spotFetchedAt: "2026-10-16T02:00:00Z", spotIsStale,
    ...(spotIsStale ? { spotStaleReason: "KOSPI: HTTP 500", spotStaleSince: "2026-10-16T02:00:00Z" } : {}),
  });

  it.fails("현물·선물 카드가 이어받은 현물에 '이전 값'과 사유·받은 시각을 붙인다", async () => {
    api.apiGetShared.mockImplementation((path: string) =>
      path.startsWith("/api/krx/spot-futures") ? Promise.resolve(body(true)) : new Promise(() => {}));

    const { SpotFuturesCard } = await import("../krx/SpotFuturesCard");
    render(<SpotFuturesCard />);
    await screen.findByText(/현물 기준일 2026-10-16/);

    const banner = screen.getByText(/이전 값/).textContent;
    expect(banner).toContain("KOSPI: HTTP 500");
    expect(banner).toContain("2026-10-16 11:00");
  });

  it("새로 받은 현물에는 '이전 값'을 붙이지 않는다", async () => {
    api.apiGetShared.mockImplementation((path: string) =>
      path.startsWith("/api/krx/spot-futures") ? Promise.resolve(body(false)) : new Promise(() => {}));

    const { SpotFuturesCard } = await import("../krx/SpotFuturesCard");
    render(<SpotFuturesCard />);
    await screen.findByText(/현물 기준일 2026-10-16/);

    expect(screen.queryByText(/이전 값/)).toBeNull();
  });
});
