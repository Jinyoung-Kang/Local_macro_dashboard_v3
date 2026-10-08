import { describe, expect, it } from "vitest";
import { parseInline, parseMarkdown, sectionKind, sectionName, splitRow, splitSections } from "../markdown";

const REPORT = `## 1. 핵심 요약
- **[금리]** 실질금리가 높습니다 (2.91%)
- **[유동성]** 순유동성 5.792조 달러

## 2. 데이터 신뢰도
| 항목 | 상태 | 영향 |
|---|---|---|
| MOVE | ⚠️ 추정치 | 임계치 적용 안 함 |

## 3. 금리와 달러
문단 첫 줄
이어지는 줄

---
> 인용문`;

describe("parseMarkdown", () => {
  it("제목·목록·표·문단·구분선·인용을 읽는다", () => {
    const blocks = parseMarkdown(REPORT);
    expect(blocks.map((b) => b.type)).toEqual([
      "heading", "list", "heading", "table", "heading", "paragraph", "hr", "quote",
    ]);
    expect(blocks[1]).toEqual({
      type: "list", ordered: false,
      items: ["**[금리]** 실질금리가 높습니다 (2.91%)", "**[유동성]** 순유동성 5.792조 달러"],
    });
    expect(blocks[3]).toEqual({
      type: "table", header: ["항목", "상태", "영향"], rows: [["MOVE", "⚠️ 추정치", "임계치 적용 안 함"]],
    });
    expect(blocks[5]).toEqual({ type: "paragraph", text: "문단 첫 줄 이어지는 줄" });
  });

  it("구분선 줄이 없으면 파이프가 든 문장은 표가 아니다", () => {
    expect(parseMarkdown("A | B 비교")).toEqual([{ type: "paragraph", text: "A | B 비교" }]);
  });

  it("칸 수가 다른 행은 머리에 맞춘다 — 모자라면 빈 칸, 넘치면 마지막 칸에 합친다", () => {
    const [table] = parseMarkdown("| a | b | c |\n|---|---|---|\n| 1 |\n| 1 | 2 | 3 | 4 |");
    expect(table).toEqual({ type: "table", header: ["a", "b", "c"], rows: [["1", "", ""], ["1", "2", "3 | 4"]] });
  });

  it("번호 목록과 코드 울타리", () => {
    expect(parseMarkdown("1. 첫째\n2) 둘째")).toEqual([{ type: "list", ordered: true, items: ["첫째", "둘째"] }]);
    expect(parseMarkdown("```\n안의 줄\n```")).toEqual([{ type: "paragraph", text: "안의 줄" }]);
  });

  it("HTML은 해석하지 않고 글자로 남긴다", () => {
    expect(parseMarkdown("<script>alert(1)</script>")).toEqual([
      { type: "paragraph", text: "<script>alert(1)</script>" },
    ]);
  });

  it("빈 입력과 CRLF", () => {
    expect(parseMarkdown("")).toEqual([]);
    expect(parseMarkdown("## 제목\r\n본문")).toEqual([
      { type: "heading", level: 2, text: "제목" }, { type: "paragraph", text: "본문" },
    ]);
  });
});

describe("parseInline", () => {
  it("굵게와 코드를 나누고, 닫히지 않은 표시는 글자로 둔다", () => {
    expect(parseInline("**[금리]** 상승 `5.33%`")).toEqual([
      { text: "[금리]", bold: true }, { text: " 상승 " }, { text: "5.33%", code: true },
    ]);
    expect(parseInline("**닫히지 않음")).toEqual([{ text: "**닫히지 않음" }]);
  });
});

describe("splitSections", () => {
  it("## 기준으로 절을 나누고 번호를 뗀 이름을 붙인다", () => {
    const sections = splitSections(parseMarkdown(REPORT));
    expect(sections.map((s) => s.name)).toEqual(["핵심 요약", "데이터 신뢰도", "금리와 달러"]);
    expect(sections.map((s) => s.id)).toEqual(["section-1", "section-2", "section-3"]);
    expect(sections[0].blocks[0].type).toBe("list");
  });

  it("모델이 ###로 절을 쓰고 맨 위에 # 제목을 하나 두어도 같은 모양으로 나눈다", () => {
    const sections = splitSections(parseMarkdown("# 브리핑\n### 1. 핵심 요약\n- a\n### 2. 시나리오\n- b"));
    expect(sections.map((s) => s.title)).toEqual(["1. 핵심 요약", "2. 시나리오"]);
  });

  it("첫 제목 앞의 내용은 제목 없는 절로 남긴다", () => {
    const sections = splitSections(parseMarkdown("머리말\n## 1. 요약\n- a"));
    expect(sections[0]).toMatchObject({ title: null, blocks: [{ type: "paragraph", text: "머리말" }] });
  });

  it("제목이 하나도 없으면 한 절", () => {
    expect(splitSections(parseMarkdown("그냥 문단"))).toHaveLength(1);
  });
});

describe("절 이름과 종류", () => {
  it("번호를 떼고 요약·신뢰도를 알아본다", () => {
    expect(sectionName("1. 핵심 요약")).toBe("핵심 요약");
    expect(sectionName("② 시나리오")).toBe("시나리오");
    expect(sectionKind("핵심 요약")).toBe("summary");
    expect(sectionKind("데이터 신뢰도")).toBe("reliability");
    expect(sectionKind("시나리오")).toBe("normal");
    expect(splitRow("| a | b |")).toEqual(["a", "b"]);
  });
});
