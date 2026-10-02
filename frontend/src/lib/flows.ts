import { EMPTY } from "./format";

/**
 * 연속 순매수·순매도 일수 표기. 받은 기록 전부가 같은 방향이면 실제로는 더 길 수 있어 "+"를 붙입니다.
 *
 * @param streak  양수 = 연속 순매수 일수, 음수 = 연속 순매도 일수. 모르면 undefined
 * @param records 받은 기록 수
 */
export function streakText(streak: number | null | undefined, records: number): string {
  if (!streak) return EMPTY;
  const days = Math.abs(streak);
  const label = days >= records ? `${days}일+` : `${days}일`;
  return `${label} ${streak > 0 ? "순매수" : "순매도"}`;
}
