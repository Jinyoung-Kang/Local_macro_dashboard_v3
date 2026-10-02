"use client";

import { useMemo } from "react";
import { useApi } from "@/hooks/useApi";
import { endpoints } from "@/lib/endpoints";
import { kstYear } from "@/lib/marketSessions";
import type { KrHolidaysResponse } from "@/lib/types";

/**
 * 올해(한국 시간 기준) 공휴일(천문연 공식 목록). 없으면 null → 내장 표로 판정합니다.
 *
 * 연도는 브라우저 현지 시간이 아니라 한국 시간으로 정합니다. 미국에서 보는 1월 1일 KST 00~09시에
 * 브라우저는 아직 12월 31일이라 전년도 목록을 고르고, 비어 있지 않은 공식 목록이 내장 표를
 * 대체하므로 신정이 "개장"으로 보였습니다.
 */
export function useKrOfficialHolidays(now: Date | null): string[] | null {
  const { data } = useApi<KrHolidaysResponse>(endpoints.publicData.krHolidays);
  const year = now ? kstYear(now) : null;
  return useMemo(
    () => (year === null ? null : data?.years?.[String(year)]?.holidays.map((day) => day.date) ?? null),
    [data, year],
  );
}
