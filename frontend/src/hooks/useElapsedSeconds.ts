"use client";

import { useEffect, useState } from "react";

/**
 * active가 켜진 뒤 지난 초. 켜질 때마다 0부터 다시 셉니다(꺼지면 멈추고 마지막 값을 유지).
 *
 * 오래 걸리는 요청(AI 리포트 등) 중에 버튼이 "생성 중…"으로만 멈춰 있으면 진행 중인지 멈춘
 * 건지 알 수 없어, 사용자가 새로고침으로 요청을 날려 버립니다. 경과 시간을 함께 보여 줍니다.
 */
export function useElapsedSeconds(active: boolean): number {
  const [elapsed, setElapsed] = useState(0);

  useEffect(() => {
    if (!active) {
      return;
    }
    const startedAt = Date.now();
    setElapsed(0);
    const timer = setInterval(() => setElapsed(Math.floor((Date.now() - startedAt) / 1000)), 1000);
    return () => clearInterval(timer);
  }, [active]);

  return elapsed;
}
