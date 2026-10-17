/**
 * '모름'을 '정상'·'없음'으로 보여 주지 않는다 — 조회가 실패하거나 값을 확인하지 못했을 때의 화면 문구.
 * (PRINCIPLES: 실패는 실패로, 모르는 값은 "—")
 */
import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => {
  class UnauthorizedError extends Error {}
  return { apiGet: vi.fn(), apiPost: vi.fn(), apiGetShared: vi.fn(), UnauthorizedError };
});
vi.mock("@/lib/api", () => api);
// 매번 같은 함수를 돌려줘야 합니다 — 새 함수면 그것을 의존성으로 쓰는 effect가 끝없이 다시 돕니다.
const signal = vi.hoisted(() => ({ token: 0, reloadAll: () => {}, notifyUnauthorized: () => {} }));
vi.mock("@/hooks/useRefreshSignal", () => ({ useRefreshSignal: () => signal }));

import AlertsPage from "../alerts/page";
import CotPage from "../cot/page";
import { SingleChartSection } from "../macro/SingleChartSection";
import RadarPage from "../radar/page";
import StatusPage from "../status/page";

/** 경로 앞부분 → 응답(함수면 거절할 수도 있음). 없는 경로는 끝나지 않는 요청. */
let reads: Record<string, () => Promise<unknown>> = {};

beforeEach(() => {
  reads = {};
  api.apiGetShared.mockReset();
  api.apiGetShared.mockImplementation((path: string) => {
    const key = Object.keys(reads).find((prefix) => path.startsWith(prefix));
    return key ? reads[key]() : new Promise(() => {});
  });
});

afterEach(() => cleanup());

describe("QA-045 상태 화면 — 수집기에 닿지 못하면 누락 여부를 모른다", () => {
  // 수정 전에는 missingDatasets가 없으면(수집기 불통) '누락된 데이터셋이 없습니다'를 초록으로 보였다.
  it("누락 목록을 받지 못했으면 '없습니다'가 아니라 '확인하지 못했습니다'", async () => {
    reads["/api/status/issues"] = () => new Promise(() => {});
    reads["/api/status"] = () => Promise.resolve({ readMode: "auto", collectorReachable: false, taskSummary: [] });

    render(<StatusPage />);

    expect(await screen.findByText(/누락 여부를 확인하지 못했습니다/)).toBeTruthy();
    expect(screen.queryByText("누락된 데이터셋이 없습니다.")).toBeNull();
  });
});

describe("QA-102 상태 화면 오류·경고 로그 — 수집기에 닿지 못하면 누락 데이터셋 수를 모른다", () => {
  // 수정 전에는 누락 수를 모르는데도(null) '0개'로 적고, 실행 기록에 실패가 없으면 초록 '오류·경고가 없습니다'를 보였다.
  it.fails("누락 데이터셋은 '—'이고 초록 '오류·경고가 없습니다'를 보이지 않는다", async () => {
    reads["/api/status/issues"] = () => Promise.resolve({
      generatedAt: "2026-10-18 09:00 KST", collectorReachable: false, lookbackHours: 24, lastRunStatus: null,
      counts: { errors: 0, warnings: 0, recentGroups: 0, missingDatasets: null },
      current: [], recent: [], missing: null, text: "요약: … 누락 데이터셋 확인 못 함(수집기 연결 안 됨)",
    });
    reads["/api/status"] = () => Promise.resolve({ readMode: "auto", collectorReachable: false, taskSummary: [] });

    render(<StatusPage />);
    const section = (await screen.findByRole("heading", { name: "수집 오류·경고 로그" })).closest("section") as HTMLElement;
    const metric = (await within(section).findByText("누락 데이터셋")).parentElement as HTMLElement;

    expect(metric.textContent).toBe("누락 데이터셋—");
    expect(within(section).queryByText(/오류·경고가 없습니다/)).toBeNull();
  });
});

describe("QA-046 수급 레이더 — 이력 조회가 실패하면 '이력 없음'이 아니다", () => {
  // 수정 전에는 오류를 받지 않아 500·연결 실패에도 '아직 누적된 이력이 없습니다'를 보였다.
  it("이력 조회 실패는 오류로 보이고 '아직 누적된 이력이 없습니다'를 적지 않는다", async () => {
    reads["/api/radar/history"] = () => Promise.reject(new Error("요청 실패 (HTTP 500)"));

    render(<RadarPage />);

    expect(await screen.findByText(/요청 실패 \(HTTP 500\)/)).toBeTruthy();
    expect(screen.queryByText(/아직 누적된 이력이 없습니다/)).toBeNull();
  });
});

describe("QA-050 실패·로딩을 '없음'으로 보이지 않는다", () => {
  // 수정 전에는 COT 개요 조회의 오류를 받지 않아 카드가 빈 채로 남았다.
  it("COT 개요 조회가 실패하면 오류를 보인다", async () => {
    reads["/api/cot/overview"] = () => Promise.reject(new Error("요청 실패 (HTTP 503)"));

    render(<CotPage />);

    expect(await screen.findByText(/요청 실패 \(HTTP 503\)/)).toBeTruthy();
  });

  // 수정 전에는 알림함을 읽는 동안(그리고 오류에도) '아직 알림이 없습니다'와 '읽지 않은 알림 0건'이 보였다.
  it("알림함을 읽는 동안에는 '아직 알림이 없습니다'·'0건'이 아니다", async () => {
    reads["/api/alerts/rules"] = () => Promise.resolve({ available: true, rules: [] });   // 알림함만 읽는 중
    render(<AlertsPage />);
    await screen.findByText("알림함");

    expect(screen.queryByText(/아직 알림이 없습니다/)).toBeNull();
    expect(screen.queryByText(/읽지 않은 알림 0건/)).toBeNull();
  });
});

describe("QA-050 자동 갱신이 실패하면 수집 시각 배지 옆에 알린다", () => {
  it("refreshError가 있으면 '새로 읽지 못함'(이유는 title), 없으면 보이지 않는다", async () => {
    const { Freshness } = await import("@/components/ui");
    const { rerender } = render(<Freshness collectedAt="2026-10-11 09:00 KST" refreshError="백엔드 API에 연결하지 못했습니다" />);
    expect(screen.getByText("⚠️ 새로 읽지 못함").getAttribute("title")).toContain("백엔드 API에 연결하지 못했습니다");

    rerender(<Freshness collectedAt="2026-10-11 09:00 KST" />);
    expect(screen.queryByText("⚠️ 새로 읽지 못함")).toBeNull();
  });
});

describe("QA-099 지표별 단독 차트 — 백엔드가 받지 못한 이유를 보인다", () => {
  // 수정 전에는 available·message를 보지 않아, 공개 데모(store_only)에서 10개 중 8개 지표가 이유 없이
  // '표시할 시계열이 없습니다'만 보였다(수집기 실패 사유도 같은 식으로 버려졌다).
  it.each([
    "store_only 모드에서는 저장하지 않는 지표의 차트를 조회하지 않습니다.",
    "수집기에서 시계열을 받지 못했습니다.",
  ])("응답이 '%s'이면 그 문구를 보이고 빈 차트를 그리지 않는다", async (message) => {
    reads["/api/macro/ticker"] = () => Promise.resolve({ available: false, symbol: "^VIX", points: [], message });

    render(<SingleChartSection />);

    expect(await screen.findByText(message, {}, { timeout: 500 })).toBeTruthy();
    expect(screen.queryByText("표시할 시계열이 없습니다.")).toBeNull();
  });
});
