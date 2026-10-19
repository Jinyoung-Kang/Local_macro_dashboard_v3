/**
 * 개장 배지의 휴장 추정 — 장 밖 값의 거래일로도 판정한다(QA-138).
 *
 * 카드 값을 TradingView 실시간 시세로 바꾸면 카드 시각은 체결 시각이 아니라 '수집 시각'이나 '거래일'입니다. 예전 휴장
 * 추정은 체결 시각(Yahoo 분봉)만 봐서, 공휴일 목록이 없는 도쿄·홍콩의 휴장일에 배지가 '개장'으로 남습니다.
 * 2026-10-19(월)은 홍콩 중양절 대체 휴장 — 그날 TradingView 항셍의 마지막 거래일은 10-16(금)이었습니다.
 */
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { SessionBadge } from "../SessionBadge";

afterEach(() => cleanup());

const HK_1100 = new Date("2026-10-19T03:00:00Z"); // 홍콩 월요일 11:00 — 시간표로는 개장
const COLLECTED = "2026-10-19 11:55 KST"; // 홍콩 10:55

function badge(lastTs: string) {
  render(<SessionBadge market="hkex" now={HK_1100} krOfficial={null} lastTs={lastTs} collectedAt={COLLECTED} />);
}

describe("QA-138 장 밖 거래일로 본 휴장 추정", () => {
  it.fails("개장 시간인데 마지막 거래일이 그 시장의 오늘보다 앞서면 '휴장 추정'", () => {
    badge("2026-10-16 거래일 기준 (TradingView · 장 밖)");
    expect(screen.getByText("휴장 추정")).toBeTruthy();
  });

  it("마지막 거래일이 오늘이면 그대로 '개장'", () => {
    badge("2026-10-19 거래일 기준 (TradingView · 장 밖)");
    expect(screen.getByText("개장")).toBeTruthy();
  });

  it("수집 시각 표기는 거래일로 읽지 않는다", () => {
    badge("2026-10-19 11:50 KST (TradingView 수집 시각)");
    expect(screen.getByText("개장")).toBeTruthy();
  });
});
