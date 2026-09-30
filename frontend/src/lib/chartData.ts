/**
 * 막대 차트용 데이터.
 *
 * 값을 모르는 항목(null·undefined)은 **막대에서 뺍니다**. 0으로 바꾸면 "순매수 0억"이라는
 * 사실처럼 보입니다 — 이 프로젝트는 모르는 값을 0으로 채우지 않습니다(docs/PRINCIPLES.md).
 * 뺀 개수를 함께 돌려주니 화면이 "N개는 금액을 몰라 뺐다"고 알릴 수 있고, 표에는 "—"로 남습니다.
 *
 * @param rows  서버가 준 순서 그대로의 행
 * @param name  막대 이름
 * @param value 막대 값. 모르면 null·undefined
 * @returns 그릴 막대(순서 유지)와 뺀 개수
 */
export function knownBars<T>(
  rows: readonly T[],
  name: (row: T) => string,
  value: (row: T) => number | null | undefined,
): { bars: { name: string; value: number }[]; omitted: number } {
  const bars: { name: string; value: number }[] = [];
  let omitted = 0;
  for (const row of rows) {
    const v = value(row);
    if (v === null || v === undefined || Number.isNaN(v)) {
      omitted += 1;
    } else {
      bars.push({ name: name(row), value: v });
    }
  }
  return { bars, omitted };
}

/**
 * 여러 계열을 날짜로 맞춘 차트 행. 날짜는 합집합이고 오래된 날이 앞입니다.
 *
 * 계열마다 거래일이 다릅니다(시장별 휴일). 값이 없는 날은 그 계열 칸을 **비워 둡니다** —
 * 배열 순서로 짝지으면 날짜가 어긋나고, 없는 날을 채우면 실제로는 없던 흐름이 생깁니다.
 *
 * @param series 계열마다 차트 키와 (날짜, 값) 목록
 * @returns `{ date, [key]: value }` 행 목록 (값이 null이면 null 그대로)
 */
export function mergeByDate(
  series: readonly { key: string; points: readonly { date: string; value: number | null }[] }[],
): Record<string, string | number | null>[] {
  const byDate = new Map<string, Record<string, string | number | null>>();
  series.forEach(({ key, points }) => {
    points.forEach((point) => {
      const row = byDate.get(point.date) ?? { date: point.date };
      row[key] = point.value;
      byDate.set(point.date, row);
    });
  });
  return [...byDate.values()].sort((left, right) => String(left.date).localeCompare(String(right.date)));
}
