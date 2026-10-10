/**
 * 모르는 값에 단위를 붙이지 않는다(QA-049) — "—%"·"—억 원"·" 일 전 공시"는 값이 있는 것처럼 읽힙니다.
 * PRINCIPLES: 값이 없으면 "—".
 */
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => {
  class UnauthorizedError extends Error {}
  return { apiGet: vi.fn(), apiPost: vi.fn(), apiGetShared: vi.fn(() => new Promise(() => {})), UnauthorizedError };
});
vi.mock("@/lib/api", () => api);
const signal = vi.hoisted(() => ({ token: 0, reloadAll: () => {}, notifyUnauthorized: () => {} }));
vi.mock("@/hooks/useRefreshSignal", () => ({ useRefreshSignal: () => signal }));

import { AdvancedSection } from "../macro/AdvancedSection";

afterEach(() => cleanup());

describe("QA-049 모르는 값에 단위를 붙이지 않는다", () => {
  // 수정 전에는 수집은 됐지만 값이 비면 "—%p"로 찍혔다.
  it.fails("심화 지표: 값이 없으면 '—%p'가 아니라 '—'", () => {
    render(
      <AdvancedSection
        loading={false}
        data={{
          order: ["T10Y3M"],
          latest: {
            T10Y3M: {
              id: "T10Y3M", label: "10Y-3M", unit: "%p", digits: 2, group: "금리", why: "", source: "FRED",
              available: true, value: null, delta: null,
            },
          },
          derived: {},
        }}
      />,
    );

    expect(screen.queryByText("—%p")).toBeNull();
    expect(screen.getAllByText("—").length).toBeGreaterThan(0);
  });
});
