"use client";

import { useCallback, useEffect, useRef, useState } from "react";

/** 오류를 화면 문구로. Error가 아니면(드묾) 기본 문구를 씁니다. */
export function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

/** 화면을 떠났는지. 떠난 뒤 끝난 요청의 결과는 버립니다. */
export function useMountedRef() {
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  return mounted;
}

/**
 * 버튼으로 서버를 부르는 동작 하나의 상태(진행 중·결과·오류).
 *
 * 화면마다 같은 `busy`/`result`/`error` 세 줄과 try/catch/finally를 반복하던 것을 모았습니다.
 *
 * @param action         실제로 부를 함수
 * @param failureMessage 오류가 Error가 아닐 때 보여 줄 문구
 * @returns `{ run, busy, result, error }`
 *   - run: 부르기. 시작할 때 오류를 지우고, 실패해도 앞서 받은 결과는 남깁니다
 *   - 화면을 떠난 뒤 끝나면 상태를 바꾸지 않습니다
 */
export function useAsyncAction<Args extends unknown[], T>(
  action: (...args: Args) => Promise<T>,
  failureMessage: string,
) {
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const mounted = useMountedRef();

  const run = useCallback(
    async (...args: Args) => {
      setBusy(true);
      setError(null);
      try {
        const value = await action(...args);
        if (mounted.current) setResult(value);
      } catch (err) {
        if (mounted.current) setError(errorMessage(err, failureMessage));
      } finally {
        if (mounted.current) setBusy(false);
      }
    },
    [action, failureMessage, mounted],
  );

  return { run, busy, result, error };
}
