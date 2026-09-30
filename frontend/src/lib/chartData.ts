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
