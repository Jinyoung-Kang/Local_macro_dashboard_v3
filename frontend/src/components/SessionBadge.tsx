"use client";

import { useEffect, useMemo, useState } from "react";
import { useApi } from "@/hooks/useApi";
import { sessionStatus, tradeTimeFrom, type SessionState } from "@/lib/marketSessions";
import type { KrHolidaysResponse } from "@/lib/types";
import { endpoints } from "@/lib/endpoints";

/**
 * 지표 카드의 "개장/마감" 배지.
 *
 * 판정은 `lib/marketSessions`(순수 함수)가 하고, 여기서는 시각을 1분마다 갱신해
 * 다시 그리기만 합니다. 데이터를 다시 받지 않으므로 서버 부하가 없습니다.
 *
 * 색 — 초록(개장)만 눈에 띄게 하고 나머지는 회색 계열입니다. 이 화면의 빨강·파랑은
 * 상승·하락 전용이라 상태 표시에 쓰지 않습니다.
 */
const STYLES: Record<SessionState, string> = {
  open: "border-ok/40 bg-ok/10 text-ok",
  break: "border-warn/40 bg-warn/10 text-warn",
  closed: "border-border bg-surface-hover text-muted",
  holiday: "border-border bg-surface-hover text-muted",
  idle: "border-border bg-surface-hover text-muted",
};

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

/** 올해 한국 공휴일(천문연 공식 목록). 없으면 null → 내장 표로 판정합니다. */
export function useKrOfficialHolidays(now: Date | null): string[] | null {
  const { data } = useApi<KrHolidaysResponse>(endpoints.publicData.krHolidays);
  const year = now ? now.getFullYear() : null;
  return useMemo(
    () => (year === null ? null : data?.years?.[String(year)]?.holidays.map((day) => day.date) ?? null),
    [data, year],
  );
}

export function SessionBadge({
  market,
  now,
  krOfficial,
  lastTs,
  collectedAt,
}: {
  market?: string | null;
  now: Date | null;
  krOfficial: string[] | null;
  /** 카드의 lastTs. 체결 시각 형식일 때만 휴장 추정에 씁니다 */
  lastTs?: string | null;
  /** 이 카드를 받은 수집 시각 ("YYYY-MM-DD HH:mm KST") */
  collectedAt?: string | null;
}) {
  if (!now) return null;
  const status = sessionStatus(market, now, krOfficial, tradeTimeFrom(lastTs), tradeTimeFrom(collectedAt));
  if (!status) return null;
  return (
    <span
      className={`rounded border px-2 py-0.5 text-[11px] ${STYLES[status.state]}`}
      title={status.detail}
    >
      {status.label}
    </span>
  );
}
