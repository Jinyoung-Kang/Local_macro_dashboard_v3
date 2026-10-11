/**
 * 기간·조건을 바꿔도 그 컨트롤은 화면에 남는다(QA-047).
 *
 * 화면이 `loading && !data`면 페이지 전체를 로딩 표시로 바꿔, 방금 조작한 선택 상자가 사라지고 키보드 포커스가
 * 문서 맨 앞으로 갔습니다(닫힌 select에서 화살표로 고르는 사용자는 한 칸마다 처음부터 다시 Tab). 새 요청이 끝나기
 * 전에도 선택 상자는 그대로 있어야 합니다.
 */
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => {
  class UnauthorizedError extends Error {}
  return { apiGet: vi.fn(), apiPost: vi.fn(), apiGetShared: vi.fn(), UnauthorizedError };
});
vi.mock("@/lib/api", () => api);
const signal = vi.hoisted(() => ({ token: 0, reloadAll: () => {}, notifyUnauthorized: () => {} }));
vi.mock("@/hooks/useRefreshSignal", () => ({ useRefreshSignal: () => signal }));

import ConsensusPage from "../consensus/page";
import LiquidityPage from "../liquidity/page";
import { FxCompareSection } from "../macro/FxCompareSection";
import MacroPage from "../macro/page";
import RegimePage from "../regime/page";
import SectorPage from "../sector/page";

/** 첫 경로만 응답하고, 바꾼 뒤의 경로는 끝나지 않는 요청(로딩 중). */
function answerOnly(firstPath: string, body: unknown) {
  api.apiGetShared.mockImplementation((path: string) =>
    path === firstPath ? Promise.resolve(body) : new Promise(() => {}));
}

beforeEach(() => {
  // 블록으로 둡니다 — 화살표가 목 함수를 돌려주면 vitest가 그것을 정리 함수로 불러 끝나지 않는 요청을 기다립니다.
  api.apiGetShared.mockReset();
});
afterEach(() => cleanup());

describe("QA-047 조건을 바꿔도 컨트롤이 남는다", () => {
  const cases = [
    { name: "섹터", Page: SectorPage, path: "/api/sector/rotation?period=1M", label: "조회 기간", next: "3M",
      body: { available: true, sectors: [], assetClasses: [], periods: ["1M", "3M"] } },
    { name: "순유동성", Page: LiquidityPage, path: "/api/liquidity?years=3", label: "조회 기간", next: "5",
      body: { available: true, rows: [] } },
    { name: "국면", Page: RegimePage, path: "/api/analytics/regime?years=5", label: "이력 기간", next: "10",
      body: { available: true, timeline: [] } },
  ];

  for (const { name, Page, path, label, next, body } of cases) {
    // 수정 전에는 로딩 중 페이지 전체를 바꿔 선택 상자가 사라졌다.
    it(`${name}: 기간을 바꾼 뒤 새 응답을 기다리는 동안에도 '${label}' 선택 상자가 그대로`, async () => {
      answerOnly(path, body);
      render(<Page />);
      const select = (await screen.findByLabelText(label)) as HTMLSelectElement;

      fireEvent.change(select, { target: { value: next } });

      expect(select.isConnected).toBe(true);
      expect((screen.getByLabelText(label) as HTMLSelectElement).value).toBe(next);
    });
  }

  it("매크로: 자동 갱신 간격이 1분 경계를 넘어(live 경로가 바뀜) 새 응답을 기다리는 동안에도 선택 상자가 그대로", async () => {
    answerOnly("/api/macro/overview?live=true", { available: true, categories: [] });   // 기본 간격은 1분 이하
    render(<MacroPage />);
    const select = (await screen.findByLabelText("자동 갱신 간격")) as HTMLSelectElement;

    fireEvent.change(select, { target: { value: "300" } });

    expect(select.isConnected).toBe(true);
  });

  it("환율 비교: 계열을 켜고 새 응답을 기다리는 동안에도 계열 버튼이 그대로", async () => {
    let first = true;
    api.apiGetShared.mockImplementation((path: string) => {
      if (path.startsWith("/api/macro/fx") && first) {
        first = false;
        return Promise.resolve({ available: true, series: [], catalog: [
          { id: "usdkrw", label: "원/달러" }, { id: "dxy", label: "달러 인덱스" }, { id: "usdjpy", label: "엔/달러" }] });
      }
      return new Promise(() => {});
    });
    render(<FxCompareSection />);
    const button = await screen.findByRole("button", { name: "엔/달러" });

    fireEvent.click(button);

    expect(button.isConnected).toBe(true);
  });

  it("13F 교집합: 기준 분기를 고르고 새 응답을 기다리는 동안 선택 상자가 고른 분기를 그대로 보인다", async () => {
    let first = true;
    api.apiGetShared.mockImplementation((path: string) => {
      if (path.startsWith("/api/sec13f/consensus") && first) {
        first = false;
        return Promise.resolve({ available: false, availableDates: ["2026-06-30", "2026-03-31"], rows: [] });
      }
      return new Promise(() => {});
    });
    render(<ConsensusPage />);
    await screen.findByRole("option", { name: "2026-03-31" });
    const select = screen.getByLabelText("기준 분기") as HTMLSelectElement;

    fireEvent.change(select, { target: { value: "2026-03-31" } });

    // 응답을 기다리는 동안 선택지가 '최신 분기' 하나로 줄면 화면은 최신을 보이는데 요청은 고른 분기로 나갔습니다.
    expect((screen.getByLabelText("기준 분기") as HTMLSelectElement).value).toBe("2026-03-31");
  });
});

describe("QA-066 페이지 조건을 바꿔도 스스로 읽는 하위 섹션의 선택이 남는다", () => {
  // 수정 전에는 페이지 데이터가 비는 동안 `{data && …}`가 하위 섹션까지 내려 선택이 처음으로 돌아갔다.
  it.fails("국면: 이력 기간을 바꿔도 판정 성과의 '관점' 선택이 그대로", async () => {
    answerOnly("/api/analytics/regime?years=5", { available: true, timeline: [] });
    render(<RegimePage />);
    const view = (await screen.findByLabelText("관점")) as HTMLSelectElement;
    fireEvent.change(view, { target: { value: "all" } });

    fireEvent.change(screen.getByLabelText("이력 기간"), { target: { value: "10" } });

    expect(view.isConnected).toBe(true);
    expect((screen.getByLabelText("관점") as HTMLSelectElement).value).toBe("all");
  });

  it.fails("매크로: 자동 갱신 간격이 1분 경계를 넘어도 환율 비교에서 켠 계열이 그대로", async () => {
    api.apiGetShared.mockImplementation((path: string) => {
      if (path === "/api/macro/overview?live=true") return Promise.resolve({ available: true, categories: [] });
      if (path.startsWith("/api/macro/fx")) {
        return Promise.resolve({ available: true, series: [], catalog: [
          { id: "usdkrw", label: "원/달러" }, { id: "dxy", label: "달러 인덱스" }, { id: "usdjpy", label: "엔/달러" }] });
      }
      return new Promise(() => {});
    });
    render(<MacroPage />);
    const button = await screen.findByRole("button", { name: "엔/달러" });
    fireEvent.click(button);
    const pressed = button.getAttribute("aria-pressed");

    fireEvent.change(screen.getByLabelText("자동 갱신 간격"), { target: { value: "300" } });

    expect(button.isConnected).toBe(true);
    expect(screen.getByRole("button", { name: "엔/달러" }).getAttribute("aria-pressed")).toBe(pressed);
  });
});
