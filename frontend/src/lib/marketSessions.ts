/**
 * 지표별 "개장/마감" 판정.
 *
 * 왜 화면에서 계산하는가 — 개장 여부는 "보는 시각"에 따라 바뀝니다. 수집 시점에
 * 계산해 저장하면 5분 뒤에는 틀린 값이 됩니다. 수집기는 지표마다 거래되는 시장 id
 * (`market`)만 붙이고, 판정은 이 순수 함수가 합니다(네트워크 없음).
 *
 * 판정 규칙
 *   - 거래 시간 창(window) 안이면 "개장".
 *   - 같은 거래일에 앞뒤로 창이 있는 틈이면 "휴식"(점심·일일 정비).
 *   - 주말·알려진 휴장일이면 "휴장", 그 밖은 "마감".
 *   - 휴장일 목록이 있는 시장은 KRX(천문연 공식 목록)·미국(규칙 계산)뿐입니다.
 *     나머지(일본·중국·홍콩)는 공휴일을 모릅니다. 대신 카드의 마지막 체결 시각이
 *     개장 시간 중인데도 오래 멈춰 있으면 "휴장 추정"으로 낮춥니다
 *     ({@link sessionStatus}의 `lastTradeAt`).
 *
 * 거래 시간 출처 (2026-09 확인)
 *   - KRX 주식 09:00~15:30 — 한국거래소 거래 안내
 *   - KRX 야간 파생 18:00~06:00(다음 날) — Korea Times 2025-06-09 "KRX opens
 *     independently run after-hours trading session for derivatives"
 *   - KOSPI200 선물 정규장 09:00~15:45 — 출처마다 개장이 08:45/09:00으로 달라
 *     두 출처가 모두 개장으로 보는 09:00부터 잡았습니다.
 *   - 도쿄증권거래소 09:00~11:30, 12:30~15:30 — JPX (2024-11-05 종료 30분 연장)
 *   - 오사카거래소 닛케이225 선물 08:45~15:45, 17:00~06:00 — JPX Trading Hours
 *   - 상하이 09:30~11:30, 13:00~15:00 — SSE Trading Schedule
 *   - 홍콩 주식 09:30~12:00, 13:00~16:00 — HKEX Securities Market Trading Hours
 *   - 항셍 선물 09:15~12:00, 13:00~16:30, 17:15~03:00 — HKEX Hang Seng Index Futures
 *   - NYSE·NASDAQ 09:30~16:00 ET
 *   - CME Globex(ES·NQ·CL·GC) 일 18:00 ~ 금 17:00 ET, 매일 17:00~18:00 정비 — CME Group
 *   - ICE 브렌트 01:00~23:00 런던(월요일은 일 23:00 개장) — ICE Brent Crude Futures
 *   - 외환(DXY·환율) 일 17:00 ~ 금 17:00 ET
 *   - 미국 국채 08:00~17:00 ET — SIFMA 권장 거래 시간
 *
 * 주의사항 — 조기 폐장·임시 휴장·악천후 휴장(홍콩 태풍 등)은 다루지 않습니다.
 */
import { krMarketHolidays, usMarketHolidays } from "./marketCalendar.ts";

export type SessionState = "open" | "break" | "closed" | "holiday" | "idle";

export interface SessionStatus {
  state: SessionState;
  /** 배지 문구 (짧게) */
  label: string;
  /** 툴팁 — 어떤 시간표로 판정했는지 */
  detail: string;
}

/** 요일: 0=일 … 6=토. start·end는 현지 분(0~1440). */
interface Window {
  days: readonly number[];
  start: number;
  end: number;
  /** 이 창이 속한 거래일이 전날이면 -1 (자정을 넘긴 야간장의 뒷부분). 휴장일 판정용. */
  tradeDayOffset?: -1;
}

interface MarketDef {
  name: string;
  timeZone: string;
  windows: readonly Window[];
  holidays?: "kr" | "us" | "us_bond";
  /** 설명 문구에 붙일 시간표 요약 */
  hours: string;
}

const WEEKDAYS = [1, 2, 3, 4, 5] as const;
const h = (hour: number, minute = 0) => hour * 60 + minute;

/** 자정을 넘기는 야간장: 평일 저녁 시작 → 다음 날 새벽 종료. */
function overnight(start: number, end: number): Window[] {
  return [
    { days: WEEKDAYS, start, end: h(24) },
    { days: [2, 3, 4, 5, 6], start: 0, end, tradeDayOffset: -1 },
  ];
}

export const MARKETS: Record<string, MarketDef> = {
  krx: {
    name: "한국거래소 주식",
    timeZone: "Asia/Seoul",
    windows: [{ days: WEEKDAYS, start: h(9), end: h(15, 30) }],
    holidays: "kr",
    hours: "평일 09:00~15:30 KST",
  },
  krx_futures: {
    name: "KRX 코스피200 선물",
    timeZone: "Asia/Seoul",
    windows: [{ days: WEEKDAYS, start: h(9), end: h(15, 45) }, ...overnight(h(18), h(6))],
    holidays: "kr",
    hours: "정규 09:00~15:45 · 야간 18:00~06:00 KST",
  },
  tse: {
    name: "도쿄증권거래소",
    timeZone: "Asia/Tokyo",
    windows: [
      { days: WEEKDAYS, start: h(9), end: h(11, 30) },
      { days: WEEKDAYS, start: h(12, 30), end: h(15, 30) },
    ],
    hours: "평일 09:00~11:30, 12:30~15:30 JST",
  },
  ose_futures: {
    name: "오사카거래소 닛케이225 선물",
    timeZone: "Asia/Tokyo",
    windows: [{ days: WEEKDAYS, start: h(8, 45), end: h(15, 45) }, ...overnight(h(17), h(6))],
    hours: "주간 08:45~15:45 · 야간 17:00~06:00 JST",
  },
  sse: {
    name: "상하이증권거래소",
    timeZone: "Asia/Shanghai",
    windows: [
      { days: WEEKDAYS, start: h(9, 30), end: h(11, 30) },
      { days: WEEKDAYS, start: h(13), end: h(15) },
    ],
    hours: "평일 09:30~11:30, 13:00~15:00 CST",
  },
  hkex: {
    name: "홍콩거래소 주식",
    timeZone: "Asia/Hong_Kong",
    windows: [
      { days: WEEKDAYS, start: h(9, 30), end: h(12) },
      { days: WEEKDAYS, start: h(13), end: h(16) },
    ],
    hours: "평일 09:30~12:00, 13:00~16:00 HKT",
  },
  hkex_futures: {
    name: "홍콩거래소 항셍 선물",
    timeZone: "Asia/Hong_Kong",
    windows: [
      { days: WEEKDAYS, start: h(9, 15), end: h(12) },
      { days: WEEKDAYS, start: h(13), end: h(16, 30) },
      ...overnight(h(17, 15), h(3)),
    ],
    hours: "주간 09:15~12:00, 13:00~16:30 · 야간 17:15~03:00 HKT",
  },
  nyse: {
    name: "뉴욕증권거래소·나스닥",
    timeZone: "America/New_York",
    windows: [{ days: WEEKDAYS, start: h(9, 30), end: h(16) }],
    holidays: "us",
    hours: "평일 09:30~16:00 ET",
  },
  cme: {
    name: "CME Globex",
    timeZone: "America/New_York",
    windows: [
      { days: [0], start: h(18), end: h(24) },
      { days: [1, 2, 3, 4], start: h(18), end: h(24) },
      { days: [1, 2, 3, 4, 5], start: 0, end: h(17) },
    ],
    hours: "일 18:00 ~ 금 17:00 ET (매일 17:00~18:00 정비)",
  },
  ice_brent: {
    name: "ICE 브렌트",
    timeZone: "Europe/London",
    windows: [
      { days: [0], start: h(23), end: h(24) },
      { days: [1], start: 0, end: h(23) },
      { days: [2, 3, 4, 5], start: h(1), end: h(23) },
    ],
    hours: "평일 01:00~23:00 런던 (월요일은 일 23:00 개장)",
  },
  fx: {
    name: "외환시장",
    timeZone: "America/New_York",
    windows: [
      { days: [0], start: h(17), end: h(24) },
      { days: [1, 2, 3, 4], start: 0, end: h(24) },
      { days: [5], start: 0, end: h(17) },
    ],
    hours: "일 17:00 ~ 금 17:00 ET (24시간)",
  },
  ust: {
    name: "미국 국채",
    timeZone: "America/New_York",
    windows: [{ days: WEEKDAYS, start: h(8), end: h(17) }],
    holidays: "us_bond",
    hours: "평일 08:00~17:00 ET (SIFMA 권장 시간)",
  },
};

const WEEKDAY_INDEX: Record<string, number> = { Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6 };

interface LocalParts {
  date: string;
  weekday: number;
  minutes: number;
}

const formatters = new Map<string, Intl.DateTimeFormat>();

function localParts(timeZone: string, at: Date): LocalParts {
  let formatter = formatters.get(timeZone);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat("en-CA", {
      timeZone, year: "numeric", month: "2-digit", day: "2-digit",
      hour: "2-digit", minute: "2-digit", hour12: false, weekday: "short",
    });
    formatters.set(timeZone, formatter);
  }
  const parts = Object.fromEntries(formatter.formatToParts(at).map((p) => [p.type, p.value]));
  return {
    date: `${parts.year}-${parts.month}-${parts.day}`,
    weekday: WEEKDAY_INDEX[String(parts.weekday)] ?? 0,
    // Intl은 자정을 "24"로 줄 때가 있습니다.
    minutes: (Number(parts.hour) % 24) * 60 + Number(parts.minute),
  };
}

/** YYYY-MM-DD의 전날. */
function previousDate(date: string): string {
  const [y, m, d] = date.split("-").map(Number);
  const prev = new Date(Date.UTC(y, m - 1, d - 1));
  return prev.toISOString().slice(0, 10);
}

/** 미국 채권시장 휴장: 증시 휴장일 + 컬럼버스데이·재향군인의 날 (증시는 열림). */
function usBondHolidays(year: number): Set<string> {
  const days = usMarketHolidays(year);
  const firstOct = new Date(Date.UTC(year, 9, 1)).getUTCDay();
  const columbus = 1 + ((1 - firstOct + 7) % 7) + 7;
  days.add(`${year}-10-${String(columbus).padStart(2, "0")}`);
  const veterans = new Date(Date.UTC(year, 10, 11)).getUTCDay();
  const observedDay = veterans === 6 ? 10 : veterans === 0 ? 12 : 11;
  days.add(`${year}-11-${observedDay}`);
  return days;
}

function isHoliday(def: MarketDef, date: string, krOfficial?: readonly string[] | null): boolean {
  const year = Number(date.slice(0, 4));
  switch (def.holidays) {
    case "kr":
      return krMarketHolidays(year, krOfficial).has(date);
    case "us":
      return usMarketHolidays(year).has(date);
    case "us_bond":
      return usBondHolidays(year).has(date);
    default:
      return false;
  }
}

/** 스케줄만으로 본 상태 (체결 시각 미반영). */
function scheduleState(
  def: MarketDef,
  at: Date,
  krOfficial?: readonly string[] | null,
): Exclude<SessionState, "idle"> {
  const local = localParts(def.timeZone, at);
  const todays = def.windows.filter((w) => w.days.includes(local.weekday));

  for (const window of todays) {
    if (local.minutes >= window.start && local.minutes < window.end) {
      const tradeDate = window.tradeDayOffset === -1 ? previousDate(local.date) : local.date;
      return isHoliday(def, tradeDate, krOfficial) ? "holiday" : "open";
    }
  }
  // 주말·휴장일은 "마감"이 아니라 "휴장"입니다 (일요일 저녁에 여는 시장도 열기 전까지는 휴장).
  if (local.weekday === 0 || local.weekday === 6 || isHoliday(def, local.date, krOfficial)) {
    return "holiday";
  }
  // 휴식은 "같은 거래일"의 두 창 사이만입니다. 전날 야간장이 끝난 새벽은 휴식이 아니라 마감.
  const before = todays.some((w) => w.tradeDayOffset !== -1 && w.end <= local.minutes);
  const after = todays.some((w) => w.start > local.minutes);
  return before && after ? "break" : "closed";
}

/** 개장 중인데 체결이 이만큼 멈춰 있으면 휴장으로 추정합니다 (15분 지연 + 수집 5분 + 여유). */
export const IDLE_MINUTES = 60;

const LABELS: Record<SessionState, string> = {
  open: "개장",
  break: "휴식",
  closed: "마감",
  holiday: "휴장",
  idle: "휴장 추정",
};

/**
 * 지표 하나의 개장/마감 상태.
 *
 * @param market       수집기가 붙인 시장 id ({@link MARKETS}의 키). 모르는 id면 null
 * @param now          판정 시각
 * @param krOfficial   그 해 한국 공휴일(천문연). 없으면 내장 표를 씁니다
 * @param lastTradeAt  카드의 마지막 **체결** 시각. 수집 시각이면 넘기지 마세요
 * @param collectedAt  그 체결 시각을 받은 수집 시각. 수집기가 멈춘 것을 휴장으로
 *                     오인하지 않도록, 지금이 아니라 수집 시각 기준으로 멈춤을 잽니다
 * @returns 상태, 없으면 null (배지를 그리지 않음)
 */
export function sessionStatus(
  market: string | null | undefined,
  now: Date,
  krOfficial?: readonly string[] | null,
  lastTradeAt?: Date | null,
  collectedAt?: Date | null,
): SessionStatus | null {
  const def = market ? MARKETS[market] : undefined;
  if (!def) return null;

  let state: SessionState = scheduleState(def, now, krOfficial);
  let note = "";

  if (state === "open" && lastTradeAt && collectedAt) {
    const idleMinutes = (collectedAt.getTime() - lastTradeAt.getTime()) / 60_000;
    const hourBefore = new Date(collectedAt.getTime() - IDLE_MINUTES * 60_000);
    // 수집 시각과 그 한 시간 전이 모두 개장 시간이었는데 체결이 없으면 휴장으로 봅니다.
    // 개장 직후에는 아직 체결이 안 들어왔을 수 있으므로 판정하지 않습니다.
    if (
      idleMinutes > IDLE_MINUTES
      && scheduleState(def, collectedAt, krOfficial) === "open"
      && scheduleState(def, hourBefore, krOfficial) === "open"
    ) {
      state = "idle";
      note = ` · 개장 시간인데 ${Math.round(idleMinutes / 60)}시간 넘게 체결이 없어 현지 휴장일로 추정`;
    }
  }

  const holidayNote = def.holidays ? "" : " · 현지 공휴일은 반영하지 않음";
  return {
    state,
    label: LABELS[state],
    detail: `${def.name} ${def.hours}${note}${holidayNote}`,
  };
}

/**
 * 카드의 lastTs에서 체결 시각을 꺼냅니다.
 *
 * "YYYY-MM-DD HH:mm[ KST]"만 체결 시각으로 봅니다. "(… 수집 시각)"·"일봉 기준"이
 * 붙은 값은 체결 시각이 아니므로 null입니다.
 */
export function tradeTimeFrom(lastTs: string | null | undefined): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})(?::\d{2})?(?: KST)?$/.exec((lastTs ?? "").trim());
  if (!match) return null;
  const [, y, mo, d, hh, mi] = match;
  return new Date(`${y}-${mo}-${d}T${hh}:${mi}:00+09:00`);
}

/** 한국 시간 기준 연도. 브라우저가 어느 시간대에 있든 같은 답입니다. */
export function kstYear(date: Date): number {
  return Number(new Intl.DateTimeFormat("en-US", { timeZone: "Asia/Seoul", year: "numeric" }).format(date));
}
