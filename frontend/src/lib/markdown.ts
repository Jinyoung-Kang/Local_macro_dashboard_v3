/**
 * AI 리포트용 Markdown 해석 — React를 모르는 순수 함수(ADR 0004).
 *
 * 리포트 프롬프트({@code SnapshotTextService.REPORT_COMMON_RULES})가 쓰게 한 범위만 다룹니다:
 * 제목(#~####), 문단, 목록(- · * · 1.), 표(| … |), 구분선(---), 인용(>), 굵게(**), 인라인 코드(`).
 * HTML은 해석하지 않고 글자 그대로 둡니다 — 화면은 이 결과로 React 요소를 만들 뿐
 * innerHTML을 쓰지 않으므로, 모델이 무엇을 출력해도 스크립트가 실행되지 않습니다.
 *
 * 모델이 형식을 어겨도(빈 줄 없이 표 시작, 칸 수가 다른 행, 굵게가 닫히지 않음) 깨지지 않고
 * 가장 가까운 모양으로 읽습니다. 못 읽는 줄은 문단으로 남깁니다.
 */

export type Inline = { text: string; bold?: boolean; code?: boolean };

export type Block =
  | { type: "heading"; level: number; text: string }
  | { type: "paragraph"; text: string }
  | { type: "list"; ordered: boolean; items: string[] }
  | { type: "table"; header: string[]; rows: string[][] }
  | { type: "quote"; text: string }
  | { type: "hr" };

export type Section = {
  /** 절 제목(번호 포함, 예: "1. 핵심 요약"). 첫 제목 앞의 내용이면 null. */
  title: string | null;
  /** 제목에서 번호를 뗀 이름(예: "핵심 요약"). 화면이 절 종류를 알아보는 데 씁니다. */
  name: string | null;
  /** 앵커용 id ("section-1" …). */
  id: string;
  blocks: Block[];
};

const HEADING = /^(#{1,4})\s+(.+?)\s*#*\s*$/;
const BULLET = /^\s*[-*•]\s+(.*)$/;
const ORDERED = /^\s*\d+[.)]\s+(.*)$/;
const TABLE_ROW = /^\s*\|.*\|\s*$/;
const TABLE_DIVIDER = /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/;
const HR = /^\s*(-{3,}|\*{3,}|_{3,})\s*$/;
const QUOTE = /^\s*>\s?(.*)$/;
const FENCE = /^\s*```/;

/** 표의 한 줄을 칸으로. 양끝 파이프를 떼고, 칸 안의 공백을 다듬습니다. */
export function splitRow(line: string): string[] {
  let body = line.trim();
  if (body.startsWith("|")) body = body.slice(1);
  if (body.endsWith("|")) body = body.slice(0, -1);
  return body.split("|").map((cell) => cell.trim());
}

/** 텍스트 전체를 블록 목록으로. */
export function parseMarkdown(source: string): Block[] {
  const lines = (source ?? "").replace(/\r\n?/g, "\n").split("\n");
  const blocks: Block[] = [];
  let paragraph: string[] = [];

  const flushParagraph = () => {
    if (paragraph.length > 0) {
      blocks.push({ type: "paragraph", text: paragraph.join(" ").trim() });
      paragraph = [];
    }
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    if (line.trim() === "") {
      flushParagraph();
      continue;
    }
    // 코드 블록은 쓰지 말라고 했지만, 나오면 울타리만 버리고 안의 줄은 문단으로 둡니다.
    if (FENCE.test(line)) {
      flushParagraph();
      continue;
    }

    const heading = HEADING.exec(line);
    if (heading) {
      flushParagraph();
      blocks.push({ type: "heading", level: heading[1].length, text: heading[2].trim() });
      continue;
    }

    // 표: 머리 줄 다음 줄이 구분선이어야 표로 봅니다. 아니면 파이프가 든 평범한 문장입니다.
    if (TABLE_ROW.test(line) && i + 1 < lines.length && TABLE_DIVIDER.test(lines[i + 1])) {
      flushParagraph();
      const header = splitRow(line);
      const rows: string[][] = [];
      i += 2;
      while (i < lines.length && TABLE_ROW.test(lines[i]) && !TABLE_DIVIDER.test(lines[i])) {
        const cells = splitRow(lines[i]);
        // 칸 수를 머리에 맞춥니다 — 모자라면 빈 칸, 넘치면 마지막 칸에 합칩니다.
        while (cells.length < header.length) cells.push("");
        if (cells.length > header.length) {
          cells.splice(header.length - 1, cells.length, cells.slice(header.length - 1).join(" | "));
        }
        rows.push(cells);
        i++;
      }
      i--;
      blocks.push({ type: "table", header, rows });
      continue;
    }

    if (HR.test(line)) {
      flushParagraph();
      blocks.push({ type: "hr" });
      continue;
    }

    const bullet = BULLET.exec(line);
    const ordered = bullet ? null : ORDERED.exec(line);
    if (bullet || ordered) {
      flushParagraph();
      const isOrdered = Boolean(ordered);
      const items: string[] = [(bullet ?? ordered)![1].trim()];
      while (i + 1 < lines.length) {
        const next = lines[i + 1];
        const match = isOrdered ? ORDERED.exec(next) : BULLET.exec(next);
        if (!match) break;
        items.push(match[1].trim());
        i++;
      }
      blocks.push({ type: "list", ordered: isOrdered, items });
      continue;
    }

    const quote = QUOTE.exec(line);
    if (quote) {
      flushParagraph();
      const parts = [quote[1]];
      while (i + 1 < lines.length && QUOTE.test(lines[i + 1])) {
        parts.push(QUOTE.exec(lines[i + 1])![1]);
        i++;
      }
      blocks.push({ type: "quote", text: parts.join(" ").trim() });
      continue;
    }

    paragraph.push(line.trim());
  }
  flushParagraph();
  return blocks;
}

/** 한 줄 안의 **굵게**와 `코드`. 닫히지 않은 표시는 글자 그대로 둡니다. */
export function parseInline(text: string): Inline[] {
  const out: Inline[] = [];
  const pattern = /\*\*(.+?)\*\*|`([^`]+)`/g;
  let last = 0;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > last) out.push({ text: text.slice(last, match.index) });
    if (match[1] !== undefined) out.push({ text: match[1], bold: true });
    else out.push({ text: match[2], code: true });
    last = pattern.lastIndex;
  }
  if (last < text.length) out.push({ text: text.slice(last) });
  return out.length > 0 ? out : [{ text: "" }];
}

/**
 * 블록을 절로 나눕니다. 절의 기준은 리포트에 나온 **가장 큰 제목 수준**입니다 — 프롬프트는 ##을
 * 쓰게 하지만, 모델이 ###로 절을 쓰거나 맨 위에 # 제목을 하나 두어도 같은 모양으로 나눕니다.
 * 제목이 하나뿐인 # 은 문서 제목으로 보고 절 기준에서 뺍니다.
 */
export function splitSections(blocks: Block[]): Section[] {
  const headingLevels = blocks.filter((b) => b.type === "heading").map((b) => (b as { level: number }).level);
  let level = headingLevels.length > 0 ? Math.min(...headingLevels) : 0;
  if (level === 1 && headingLevels.filter((l) => l === 1).length === 1 && headingLevels.some((l) => l > 1)) {
    level = Math.min(...headingLevels.filter((l) => l > 1));
  }

  const sections: Section[] = [];
  let current: Section = { title: null, name: null, id: "section-0", blocks: [] };
  for (const block of blocks) {
    if (block.type === "heading" && block.level === level) {
      if (current.title !== null || current.blocks.length > 0) sections.push(current);
      const title = block.text.replace(/\*\*/g, "").trim();
      current = { title, name: sectionName(title), id: `section-${sections.length + 1}`, blocks: [] };
      continue;
    }
    if (block.type === "heading" && block.level < level) {
      continue; // 문서 제목(#) — 카드 제목이 대신합니다.
    }
    current.blocks.push(block);
  }
  if (current.title !== null || current.blocks.length > 0) sections.push(current);
  return sections;
}

/** "1. 핵심 요약" · "1) 핵심 요약" · "① 핵심 요약" → "핵심 요약". */
export function sectionName(title: string): string {
  return title.replace(/^\s*(\d+[.)]|[①-⑩])\s*/, "").trim();
}

/** 절 종류 — 화면이 강조(요약)·주의(신뢰도) 스타일을 고르는 데 씁니다. */
export function sectionKind(name: string | null): "summary" | "reliability" | "normal" {
  if (!name) return "normal";
  if (/요약|결론|summary/i.test(name)) return "summary";
  if (/신뢰도|데이터 (상태|점검|한계)|reliability/i.test(name)) return "reliability";
  return "normal";
}
