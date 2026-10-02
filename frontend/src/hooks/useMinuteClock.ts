"use client";

import { useEffect, useState } from "react";

/** 1분마다 바뀌는 현재 시각. 서버 렌더와 어긋나지 않게 마운트 후에만 값을 가집니다. */
export function useMinuteClock(): Date | null {
  const [now, setNow] = useState<Date | null>(null);
  useEffect(() => {
    setNow(new Date());
    const timer = setInterval(() => setNow(new Date()), 60_000);
    return () => clearInterval(timer);
  }, []);
  return now;
}
