/**
 * 자동 갱신(또는 새로고침)이 실패하면 그 패널에 '⚠️ 새로 읽지 못함'을 보인다(QA-067).
 *
 * 실패하면 화면은 앞서 받은 값을 그대로 보이므로, 표시가 없으면 몇 시간 전 값이 지금 값처럼 남습니다. QA-050은 수집
 * 시각 배지가 있는 9개 화면에만 붙였고, 자동 갱신하는 나머지 패널(상태 화면, 매크로 위험·고급·스크래핑, 수급 패널,
 * 현물·선물 카드, KRX 선물 수급)은 조용했습니다.
 */
import { act, cleanup, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => {
  class UnauthorizedError extends Error {}
  return { apiGet: vi.fn(), apiPost: vi.fn(), apiGetShared: vi.fn(), UnauthorizedError };
});
vi.mock("@/lib/api", () => api);
const signal = vi.hoisted(() => ({ token: 0, reloadAll: () => {}, notifyUnauthorized: () => {} }));
vi.mock("@/hooks/useRefreshSignal", () => ({ useRefreshSignal: () => signal }));

import { SpotFuturesCard } from "../krx/SpotFuturesCard";
import KrxPage from "../krx/page";
import MacroPage from "../macro/page";
import { ScrapedSection } from "../macro/ScrapedSection";
import { MarketFlowsPanel } from "../radar/FlowPanels";
import StatusPage from "../status/page";

const FAILED = "⚠️ 새로 읽지 못함";

/** 경로마다 첫 요청은 body로 답하고, 그 뒤(새로 읽기)는 실패. bodies에 없는 경로는 끝나지 않는 요청(경로는 정확히 일치). */
function firstThenFail(bodies: Record<string, unknown>) {
  const seen = new Set<string>();
  api.apiGetShared.mockImplementation((path: string) => {
    if (!(path in bodies)) return new Promise(() => {});
    if (seen.has(path)) return Promise.reject(new Error("백엔드 API에 연결하지 못했습니다"));
    seen.add(path);
    return Promise.resolve(bodies[path]);
  });
}

/**
 * 화면 전체 새로고침 신호(useApi가 따라 다시 읽음). 새 요소로 다시 그립니다 — 같은 요소를 넘기면 React가 렌더를
 * 건너뛰어 새 신호를 읽지 않습니다. 실패한 새로 읽기가 실제로 일어났는지(같은 경로 두 번째 요청)까지 확인합니다.
 */
async function refreshAll(rerender: (ui: ReactElement) => void, make: () => ReactElement, path: string) {
  signal.token += 1;
  await act(async () => rerender(make()));
  await waitFor(() =>
    expect(api.apiGetShared.mock.calls.filter(([called]) => called === path)).toHaveLength(2));
}

async function cardTitled(name: string) {
  const heading = await screen.findByRole("heading", { name });
  return heading.closest("section") as HTMLElement;
}

beforeEach(() => {
  // 블록으로 둡니다 — 화살표가 목 함수를 돌려주면 vitest가 그것을 정리 함수로 불러 끝나지 않는 요청을 기다립니다.
  api.apiGetShared.mockReset();
  signal.token = 0;
});
afterEach(() => cleanup());

describe("QA-067 자동 갱신 실패를 패널에 표시한다", () => {
  const panels: { name: string; make: () => ReactElement; bodies: Record<string, unknown>; card: string }[] = [
    { name: "현물·선물 카드", make: () => <SpotFuturesCard />, card: "현물·선물 수급 동조 (외국인·기관·개인)",
      bodies: { "/api/krx/spot-futures": { available: true, rows: [] } } },
    { name: "시장 수급 패널", make: () => <MarketFlowsPanel market="KOSPI" />, card: "코스피 전체 투자자별 매매대금",
      bodies: { "/api/kr/investor-flows": { markets: {} } } },
    { name: "스크래핑 시세", make: () => <ScrapedSection />, card: "비공식 스크래핑 시세 비교",
      bodies: { "/api/macro/scraped": { available: true, items: [] } } },
    { name: "매크로 위험 지표", make: () => <MacroPage />, card: "신용 리스크, 은행권 및 시장 변동성",
      bodies: { "/api/macro/risk": {} } },
    { name: "매크로 심화 지표", make: () => <MacroPage />, card: "심화 매크로 지표",
      bodies: { "/api/macro/advanced": { order: [], latest: {}, derived: {} } } },
    { name: "KRX 투자주체별 선물 수급", make: () => <KrxPage />, card: "투자주체별 선물 수급",
      // 페이지는 선물 응답을 받기 전까지 전체를 로딩으로 그립니다. 확인할 경로(선물 수급)는 마지막에 둡니다.
      bodies: { "/api/krx/futures?days=60": { available: false }, "/api/krx/investor-trend": { available: false } } },
    { name: "상태 화면 오류·경고 로그", make: () => <StatusPage />, card: "수집 오류·경고 로그",
      bodies: { "/api/status": { readMode: "auto", collectorReachable: true },
                "/api/status/issues": { counts: { errors: 0, warnings: 0, recentGroups: 0, missingDatasets: 0 },
                                        collectorReachable: true, text: "" } } },
  ];

  for (const { name, make, bodies, card } of panels) {
    it(`${name}: 새로 읽기에 실패하면 '새로 읽지 못함'`, async () => {
      firstThenFail(bodies);
      const { rerender } = render(make());
      const section = await cardTitled(card);
      expect(within(section).queryByText(FAILED)).toBeNull();

      await refreshAll(rerender, make, Object.keys(bodies).at(-1) as string);

      expect(await within(section).findByText(FAILED)).toBeTruthy();
    });
  }

  it("상태 화면: 개요를 새로 읽지 못하면 머리말에 '새로 읽지 못함'", async () => {
    firstThenFail({ "/api/status": { readMode: "auto", collectorReachable: true } });
    const { rerender } = render(<StatusPage />);
    const header = (await screen.findByRole("heading", { name: "데이터 저장소 상태" })).closest("header") as HTMLElement;

    await refreshAll(rerender, () => <StatusPage />, "/api/status");

    expect(await within(header).findByText(FAILED)).toBeTruthy();
  });
});
