/**
 * src/lib/transforms.ts
 * 화면에 쓰는 데이터 가공 — React를 모르는 순수 함수입니다(차트 모양 맞추기는 chartData.ts).
 */

/**
 * 대칭 행렬(기관 × 기관 유사도)에서 값이 큰 짝 순위.
 *
 * 서버의 topPairs는 겹침 비중 순이라, 화면에서 다른 기준(코사인)을 고르면 목록과 기준이
 * 어긋납니다. 고른 행렬에서 직접 만듭니다. 값이 없는 칸(null)은 순위에 넣지 않습니다.
 *
 * @param names  행·열 순서의 이름
 * @param matrix 대칭 행렬 (위쪽 삼각형만 봅니다)
 * @param label  화면에 쓸 이름으로 바꾸는 함수
 * @param limit  몇 개까지
 */
export function topPairs(
  names: readonly { name: string }[],
  matrix: readonly (readonly (number | null | undefined)[] | undefined)[],
  label: (name: string) => string,
  limit: number,
): { left: string; right: string; value: number }[] {
  const out: { left: string; right: string; value: number }[] = [];
  for (let row = 0; row < names.length; row += 1) {
    for (let col = row + 1; col < names.length; col += 1) {
      const value = matrix[row]?.[col];
      if (value !== undefined && value !== null) {
        out.push({ left: label(names[row].name), right: label(names[col].name), value });
      }
    }
  }
  return out.sort((a, b) => b.value - a.value).slice(0, limit);
}

/**
 * 값이 큰 순서로 정렬하되 값을 모르는 행은 맨 뒤로(0으로 취급하지 않음). 원본은 바꾸지 않습니다.
 */
export function sortDescUnknownLast<T>(rows: readonly T[], value: (row: T) => number | null | undefined): T[] {
  return [...rows].sort((a, b) => {
    const left = value(a);
    const right = value(b);
    if (left === null || left === undefined) {
      return 1;
    }
    if (right === null || right === undefined) {
      return -1;
    }
    return right - left;
  });
}
