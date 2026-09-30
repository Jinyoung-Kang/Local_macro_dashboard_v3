/**
 * 금리차 카드의 "변화" 칸 — 한쪽 값을 모르면 변화도 모른다("—")로 보여야 합니다.
 *
 * 예전 조건(`latest !== null && previous !== null`)은 값이 **없는 필드(undefined)** 를 통과시키고
 * `?? 0`으로 메워, 직전 값을 모르는데도 "+0.500%p"처럼 사실 같은 변화를 그렸습니다.
 * 지금 백엔드는 모르는 값을 null로 보내 드러나지 않았지만, 필드가 빠지면(버전 차이 등) 그대로 나옵니다.
 */
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { SpreadSection } from "../macro/SpreadSection";
import type { SpreadBlock } from "@/lib/types";

afterEach(() => cleanup());

const block = (fields: Partial<SpreadBlock>) =>
  ({ longId: "DGS10", shortId: "DGS2", points: [], ...fields }) as SpreadBlock;

describe("SpreadSection 변화 칸", () => {
  it("직전 값이 없으면 변화를 계산하지 않는다(0으로 메우지 않음)", () => {
    render(<SpreadSection title="10Y−2Y" block={block({ latest: 0.5 })} />);
    // 현재값 칸에만 "+0.500%p"가 있어야 합니다. 변화 칸까지 같은 글자면 0을 빼서 만든 값입니다.
    expect(screen.getAllByText("+0.500%p")).toHaveLength(1);
  });

  it("현재 값이 없으면 변화를 계산하지 않는다", () => {
    render(<SpreadSection title="10Y−2Y" block={block({ previous: 0.2 })} />);
    expect(screen.queryByText("-0.200%p")).toBeNull();
  });

  it("둘 다 있으면 차이를 보여 준다", () => {
    render(<SpreadSection title="10Y−2Y" block={block({ latest: 0.5, previous: 0.2 })} />);
    expect(screen.getByText("+0.300%p")).toBeTruthy();
  });
});
