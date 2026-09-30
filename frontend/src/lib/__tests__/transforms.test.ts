import { describe, expect, it } from "vitest";
import { knownBars, mergeByDate } from "../chartData";
import { sortDescUnknownLast, topPairs } from "../transforms";

describe("mergeByDate", () => {
  it("날짜 합집합·오래된 날이 앞, 값이 없는 날은 그 계열 칸이 비어 있다", () => {
    const rows = mergeByDate([
      { key: "usdkrw", points: [{ date: "2026-09-02", value: 1390 }, { date: "2026-09-01", value: 1385 }] },
      { key: "jpykrw", points: [{ date: "2026-09-01", value: 9.4 }, { date: "2026-09-03", value: null }] },
    ]);
    expect(rows).toEqual([
      { date: "2026-09-01", usdkrw: 1385, jpykrw: 9.4 },
      { date: "2026-09-02", usdkrw: 1390 },               // jpykrw 칸 없음(0·앞 값으로 채우지 않음)
      { date: "2026-09-03", jpykrw: null },
    ]);
  });

  it("계열이 없으면 빈 목록", () => {
    expect(mergeByDate([])).toEqual([]);
  });
});

describe("topPairs", () => {
  const names = [{ name: "🏦 A (x)" }, { name: "B" }, { name: "C" }];
  const label = (name: string) => name.replace(/^🏦 /, "").replace(/ \(x\)$/, "");

  it("위쪽 삼각형만 보고 값이 큰 순으로 자른다, 값이 없는 칸은 뺀다", () => {
    const matrix = [
      [100, 30, null],
      [30, 100, 55],
      [null, 55, 100],
    ];
    expect(topPairs(names, matrix, label, 5)).toEqual([
      { left: "B", right: "C", value: 55 },
      { left: "A", right: "B", value: 30 },
    ]);
    expect(topPairs(names, matrix, label, 1)).toHaveLength(1);
  });

  it("행렬이 모자라도 멈추지 않는다", () => {
    expect(topPairs(names, [[100]], label, 5)).toEqual([]);
  });
});

describe("sortDescUnknownLast", () => {
  it("큰 순서, 모르는 값은 맨 뒤(0으로 보지 않음), 원본은 그대로", () => {
    const rows = [{ id: "a", v: 1 }, { id: "b", v: null }, { id: "c", v: -2 }, { id: "d", v: 5 }];
    const sorted = sortDescUnknownLast(rows, (row) => row.v);
    expect(sorted.map((row) => row.id)).toEqual(["d", "a", "c", "b"]);
    expect(rows.map((row) => row.id)).toEqual(["a", "b", "c", "d"]);
  });
});

describe("knownBars (섹터 막대에도 씀)", () => {
  it("모르는 값은 빼고 순서를 유지한다 — 정렬은 호출하는 쪽", () => {
    const { bars } = knownBars(
      [{ n: "정보기술 (Technology)", r: 3 }, { n: "에너지 (Energy)", r: null }, { n: "금융 (Financials)", r: 5 }],
      (row) => row.n.split(" ")[0],
      (row) => row.r,
    );
    expect(bars).toEqual([{ name: "정보기술", value: 3 }, { name: "금융", value: 5 }]);
  });
});
