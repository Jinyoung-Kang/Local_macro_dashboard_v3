/**
 * 미국 국채 카드의 개장/마감 시간표(QA-141).
 *
 * 국채 카드 값은 TradingView 수익률(TVC:US02Y 등)이고, 미국 국채는 일요일 저녁부터 금요일 17:00 ET까지 전자거래로 거의
 * 24시간 거래됩니다 — 2026-10-20 19:54 KST(뉴욕 06:54)에도 값이 5분마다 바뀌었습니다. 시간표가 미국 장중(SIFMA 권장
 * 08:00~17:00 ET)만 '개장'으로 봐서, 움직이는 값에 '마감' 배지가 붙고 '실시간' 배지와 함께 떠 모순되게 읽혔습니다.
 */
import { describe, expect, it } from "vitest";

import { sessionStatus } from "@/lib/marketSessions";

const state = (iso: string) => sessionStatus("ust", new Date(iso))?.state;

describe("QA-141 미국 국채 시간표는 전자거래 시간(일 18:00 ~ 금 17:00 ET)", () => {
  it.fails("화요일 뉴욕 06:54(한국 19:54)는 개장 — 아시아·유럽 시간에도 거래됨", () => {
    expect(state("2026-10-20T10:54:00Z")).toBe("open");
  });

  it.fails("일요일 뉴욕 19:00은 개장 — 월요일 거래일 세션", () => {
    expect(state("2026-10-18T23:00:00Z")).toBe("open");
  });

  it.fails("평일 뉴욕 17:30은 휴식(17:00~18:00 쉼)", () => {
    expect(state("2026-10-20T21:30:00Z")).toBe("break");
  });

  it("금요일 뉴욕 17:30은 마감, 토요일은 휴장", () => {
    expect(state("2026-10-23T21:30:00Z")).toBe("closed");
    expect(state("2026-10-24T15:00:00Z")).toBe("holiday");
  });

  it.fails("컬럼버스데이(10-12 월) 전날 일요일 저녁 세션은 월요일 거래일이라 휴장, 월요일 저녁 세션은 화요일이라 개장", () => {
    expect(state("2026-10-11T23:00:00Z")).toBe("holiday");   // 일 19:00 ET
    expect(state("2026-10-12T23:00:00Z")).toBe("open");      // 월 19:00 ET
  });
});
