/**
 * useAsyncAction · useElapsedSeconds — 버튼 동작 공용 훅의 규칙.
 *  - 실패해도 앞서 받은 결과는 남기고, 다음 실행을 시작하면 오류를 지운다
 *  - Error가 아닌 것이 던져지면 기본 문구
 *  - 화면을 떠난 뒤 끝난 요청은 상태를 바꾸지 않는다
 *  - 경과 초는 켤 때마다 0부터, 끄면 멈춘다
 */
import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useAsyncAction } from "@/hooks/useAsyncAction";
import { useElapsedSeconds } from "@/hooks/useElapsedSeconds";

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("useAsyncAction", () => {
  it("성공하면 결과, 실패하면 문구(앞 결과는 유지), 다시 시작하면 문구를 지운다", async () => {
    const action = vi.fn<(n: number) => Promise<string>>()
      .mockResolvedValueOnce("첫 결과")
      .mockRejectedValueOnce(new Error("서버 오류"))
      .mockResolvedValueOnce("셋째 결과");
    const { result } = renderHook(() => useAsyncAction(action, "기본 문구"));

    await act(() => result.current.run(1));
    expect(result.current).toMatchObject({ busy: false, result: "첫 결과", error: null });

    await act(() => result.current.run(2));
    expect(result.current).toMatchObject({ busy: false, result: "첫 결과", error: "서버 오류" });

    await act(() => result.current.run(3));
    expect(result.current).toMatchObject({ result: "셋째 결과", error: null });
    expect(action.mock.calls).toEqual([[1], [2], [3]]);
  });

  it("Error가 아닌 것이 던져지면 기본 문구", async () => {
    const { result } = renderHook(() => useAsyncAction(() => Promise.reject("문자열"), "기본 문구"));
    await act(() => result.current.run());
    expect(result.current.error).toBe("기본 문구");
  });

  it("진행 중에는 busy, 화면을 떠난 뒤 끝나면 상태를 바꾸지 않는다", async () => {
    let finish!: (value: string) => void;
    const { result, unmount } = renderHook(() =>
      useAsyncAction(() => new Promise<string>((resolve) => { finish = resolve; }), "기본 문구"));

    let pending!: Promise<void>;
    act(() => {
      pending = result.current.run();
    });
    expect(result.current.busy).toBe(true);
    const seen = result.current;
    unmount();
    await act(async () => {
      finish("늦은 결과");
      await pending;
    });
    expect(result.current).toBe(seen);   // 떠난 뒤에는 그대로
  });
});

describe("useElapsedSeconds", () => {
  it("켜면 0부터 초를 세고, 끄면 멈추고, 다시 켜면 0부터", () => {
    vi.useFakeTimers();
    const { result, rerender } = renderHook(({ active }) => useElapsedSeconds(active), {
      initialProps: { active: true },
    });
    expect(result.current).toBe(0);
    act(() => vi.advanceTimersByTime(3_000));
    expect(result.current).toBe(3);

    rerender({ active: false });
    act(() => vi.advanceTimersByTime(5_000));
    expect(result.current).toBe(3);

    rerender({ active: true });
    expect(result.current).toBe(0);
  });
});
