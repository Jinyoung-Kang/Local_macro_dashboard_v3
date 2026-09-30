"use client";

import { useApi } from "@/hooks/useApi";
import { EMPTY, formatKrw, formatNumber } from "@/lib/format";
import type { UsdKrwResponse } from "@/lib/types";
import { endpoints } from "@/lib/endpoints";

/**
 * 달러 금액에 원화를 병기하기 위한 공용 환율 훅.
 *
 * @returns `{ available, rate, toKrw, trillionToKrw, billionToKrw, note, label }`
 *
 * **환산 함수는 환율을 모르면 문자열 대신 `null`을 돌려줍니다.** 호출부는 그 줄을
 * 통째로 생략하세요 — 기본 환율(예: 1,300원)로 채우면 틀린 금액을 사실처럼
 * 보여 주게 됩니다.
 *
 * 화면마다 따로 받지 않는 이유 — 같은 값을 각자 받으면 순유동성은 1,381원,
 * 13F는 1,379원으로 환산되는 일이 생깁니다. 두 화면을 나란히 보는 사람에게는
 * 둘 중 하나가 틀린 것으로 보입니다. 매크로 화면이 이미 수집한 **하나의 값**만
 * 씁니다.
 */
export function useUsdKrw() {
  // 환율은 이 화면들의 주인공이 아닙니다. 2분이면 원화 환산의 자릿수를 바꾸지
  // 않으면서 충분히 따라갑니다(카드 자체는 매크로 화면이 더 자주 읽습니다).
  const { data } = useApi<UsdKrwResponse>(endpoints.macro.usdkrw, 120_000);

  const available = Boolean(data?.available && data.rate && data.rate > 0);
  const rate = available ? (data?.rate ?? null) : null;

  const toKrw = (usd: number | null | undefined): string | null => {
    if (rate === null || usd === null || usd === undefined || Number.isNaN(usd)) {
      return null;
    }
    return `약 ${formatKrw(usd * rate)}`;
  };

  return {
    available,
    rate,
    toKrw,
    /**
     * **조 달러 단위** 값의 원화 환산 (순유동성·연준 총자산).
     *
     * @param usdInTrillions 조 달러 단위 값
     * @returns 원화 문자열 또는 `null`
     */
    trillionToKrw: (usdInTrillions: number | null | undefined) =>
      usdInTrillions === null || usdInTrillions === undefined
        ? null
        : toKrw(usdInTrillions * 1e12),
    /**
     * **십억 달러 단위** 값의 원화 환산 (TGA·RRP).
     *
     * @param usdInBillions 십억 달러 단위 값
     * @returns 원화 문자열 또는 `null`
     */
    billionToKrw: (usdInBillions: number | null | undefined) =>
      usdInBillions === null || usdInBillions === undefined
        ? null
        : toKrw(usdInBillions * 1e9),
    /** 어느 환율로 언제 환산했는지. **원화를 적은 화면은 이 문구도 함께 적습니다.** */
    note: available
      ? `원/달러 ${formatNumber(rate, 2)} 기준${data?.lastTs ? ` · ${data.lastTs}` : ""}`
      : "원/달러 값이 없어 원화 환산은 생략했습니다.",
    label: available ? `${formatNumber(rate, 2)}원` : EMPTY,
  };
}
