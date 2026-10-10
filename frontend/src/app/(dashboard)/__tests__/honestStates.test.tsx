/**
 * '모름'을 '정상'·'없음'으로 보여 주지 않는다 — 조회가 실패하거나 값을 확인하지 못했을 때의 화면 문구.
 * (PRINCIPLES: 실패는 실패로, 모르는 값은 "—")
 */
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => {
  class UnauthorizedError extends Error {}
  return { apiGet: vi.fn(), apiPost: vi.fn(), apiGetShared: vi.fn(), UnauthorizedError };
});
vi.mock("@/lib/api", () => api);
// 매번 같은 함수를 돌려줘야 합니다 — 새 함수면 그것을 의존성으로 쓰는 effect가 끝없이 다시 돕니다.
const signal = vi.hoisted(() => ({ token: 0, reloadAll: () => {}, notifyUnauthorized: () => {} }));
vi.mock("@/hooks/useRefreshSignal", () => ({ useRefreshSignal: () => signal }));

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

describe("QA-046 수급 레이더 — 이력 조회가 실패하면 '이력 없음'이 아니다", () => {
  // 수정 전에는 오류를 받지 않아 500·연결 실패에도 '아직 누적된 이력이 없습니다'를 보였다.
  it("이력 조회 실패는 오류로 보이고 '아직 누적된 이력이 없습니다'를 적지 않는다", async () => {
    reads["/api/radar/history"] = () => Promise.reject(new Error("요청 실패 (HTTP 500)"));

    render(<RadarPage />);

    expect(await screen.findByText(/요청 실패 \(HTTP 500\)/)).toBeTruthy();
    expect(screen.queryByText(/아직 누적된 이력이 없습니다/)).toBeNull();
  });
});
