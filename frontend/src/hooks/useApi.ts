"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { apiGet, UnauthorizedError } from "@/lib/api";
import { useRefreshSignal } from "@/hooks/useRefreshSignal";

interface State<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
  unauthorized: boolean;
  /** 이 브라우저가 마지막으로 응답을 받은 시각. 자동 갱신이 돌고 있는지 화면에 보이려고 씁니다. */
  loadedAt: Date | null;
}

/**
 * 백엔드 GET 요청 훅.
 *
 * @param path      `/api/...` 경로. **null이면 요청하지 않습니다**(조건부 조회에 씁니다)
 * @param refreshMs 자동 갱신 주기(ms). 0이면 갱신하지 않습니다
 * @returns `{ data, loading, error, unauthorized, loadedAt, reload }`
 *
 * 알아 둘 것
 * - 경로가 바뀌면 **이전 요청을 취소**합니다. 느린 응답이 나중에 도착해 화면을
 *   되돌리는 문제를 막습니다.
 * - 자동 갱신은 **탭을 보고 있을 때만** 돕니다. 숨으면 멈추고, 돌아오면 즉시 한 번
 *   읽고 다시 겁니다.
 * - 사이드바의 "데이터 수동 새로고침"이 끝나면 공용 신호가 바뀌어 이 훅을 쓰는
 *   모든 화면이 스스로 다시 읽습니다.
 * - 401이면 `unauthorized`가 서고 `data`는 null이 됩니다.
 * - `options.timeoutMs`를 주면 그 시간 안에 응답이 없을 때 오류로 끝냅니다.
 *   주지 않으면 기다립니다(외부 API를 부르는 진단 화면은 오래 걸리는 게 정상).
 */
export function useApi<T>(
  path: string | null,
  refreshMs = 0,
  options: { timeoutMs?: number } = {},
) {
  const timeoutMs = options.timeoutMs ?? 0;
  const { token } = useRefreshSignal();
  const [state, setState] = useState<State<T>>({
    data: null,
    loading: Boolean(path),
    error: null,
    unauthorized: false,
    loadedAt: null,
  });

  const controllerRef = useRef<AbortController | null>(null);

  const load = useCallback(async () => {
    if (!path) {
      setState({ data: null, loading: false, error: null, unauthorized: false, loadedAt: null });
      return;
    }

    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;

    setState((previous) => ({ ...previous, loading: true, error: null }));

    try {
      const data = await apiGet<T>(path, controller.signal, timeoutMs);
      setState({ data, loading: false, error: null, unauthorized: false, loadedAt: new Date() });
    } catch (error) {
      if (controller.signal.aborted) {
        return;
      }
      if (error instanceof UnauthorizedError) {
        setState({ data: null, loading: false, error: null, unauthorized: true, loadedAt: null });
        return;
      }
      setState({
        data: null,
        loading: false,
        error: error instanceof Error ? error.message : "알 수 없는 오류",
        unauthorized: false,
        loadedAt: null,
      });
    }
    // token은 값 자체를 쓰지 않습니다. 바뀌면 load가 새로 만들어지고,
    // 아래 effect가 다시 돌아 화면이 갱신됩니다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, token, timeoutMs]);

  useEffect(() => {
    void load();
    return () => controllerRef.current?.abort();
  }, [load]);

  // 탭을 백그라운드에 두면 브라우저가 타이머를 늦추기도 하지만, 그보다 큰 문제는
  // 아무도 보지 않는 화면 때문에 백엔드가 수집기를 계속 깨운다는 것입니다.
  // 매크로 화면은 10초 간격을 고를 수 있어 이 낭비가 그대로 외부 API 호출이 됩니다.
  useEffect(() => {
    if (!refreshMs || refreshMs <= 0) {
      return;
    }

    let timer: ReturnType<typeof setInterval> | null = null;

    const start = () => {
      if (timer === null) {
        timer = setInterval(() => void load(), refreshMs);
      }
    };
    const stop = () => {
      if (timer !== null) {
        clearInterval(timer);
        timer = null;
      }
    };

    const onVisibilityChange = () => {
      if (document.visibilityState === "visible") {
        void load();
        start();
      } else {
        stop();
      }
    };

    if (document.visibilityState === "visible") {
      start();
    }
    document.addEventListener("visibilitychange", onVisibilityChange);

    return () => {
      stop();
      document.removeEventListener("visibilitychange", onVisibilityChange);
    };
  }, [refreshMs, load]);

  return { ...state, reload: load };
}
