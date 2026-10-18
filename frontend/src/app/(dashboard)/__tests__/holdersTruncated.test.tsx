/**
 * 보유 기관 검색이 잘린 결과를 받으면 잘렸다는 사실을 알린다(QA-133).
 *
 * 예전에는 백엔드가 일치하는 행을 모두 돌려줘, 한 글자 검색(C)에 1만 4,748행(JSON 3MB)을 받아 모두 그렸습니다. 이제 백엔드는
 * 비중 상위 200행만 주고 원래 일치 건수(totalMatches)와 잘렸다는 표시(truncated)를 함께 내려보냅니다. 화면이 그 사실을
 * 말하지 않으면 200행이 전부인 것처럼 읽힙니다.
 */
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
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

const ROWS = [
  { institution: "버크셔 해서웨이", cik: "0001067983", name: "CITIGROUP INC", cusip: "172967424", weight: 3.2,
    value: 2_500_000_000, reportDate: "2026-06-30" },
  { institution: "노르웨이 국부펀드", cik: "0001374170", name: "COCA COLA CO", cusip: "191216100", weight: 0.6,
    value: 9_000_000_000, reportDate: "2026-06-30" },
];

function search(response: object, query: string) {
  api.apiGetShared.mockImplementation((path: string) =>
    path.startsWith("/api/guru/holders") ? Promise.resolve(response) : new Promise(() => {}));
  render(<GuruPage />);
  fireEvent.change(screen.getByPlaceholderText("NVIDIA"), { target: { value: query } });
  fireEvent.click(screen.getByRole("button", { name: "찾기" }));
}

describe("QA-133 보유 기관 검색 — 잘린 결과", () => {
  // 수정 전에는 truncated·totalMatches를 읽지 않아, 받은 행만 그리고 몇 건이 빠졌는지 말하지 않았다.
  it("잘렸으면 일치 건수와 보이는 건수를 함께 알린다", async () => {
    search({ available: true, query: "C", rows: ROWS, totalMatches: 14748, truncated: true }, "C");

    await screen.findByText("CITIGROUP INC");
    expect(screen.getByText(/일치 14,748건 중 비중 상위 2건만 보입니다/)).toBeTruthy();
  });

  it("잘리지 않았으면 알리지 않는다", async () => {
    search({ available: true, query: "CO", rows: ROWS, totalMatches: 2, truncated: false }, "CO");

    await screen.findByText("COCA COLA CO");
    expect(screen.queryByText(/비중 상위/)).toBeNull();
  });
});
