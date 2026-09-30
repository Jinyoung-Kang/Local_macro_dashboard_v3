import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { endpoints } from "../endpoints";

/** 서버가 받는 것: 경로 + (이름, 디코딩한 값) 목록(순서 포함). 인코딩 표기 차이는 무시합니다. */
function received(url: string) {
  const parsed = new URL(url, "http://backend");
  return { path: decodeURIComponent(parsed.pathname), params: [...parsed.searchParams.entries()] };
}

/**
 * 예전 화면 코드의 주소 조립식(before)을 그대로 옮겨 적고, 새 함수(after)와 같은 값으로 부릅니다.
 * 값은 화면이 실제로 쓰는 모양입니다. 서버가 받는 경로·파라미터가 같아야 합니다.
 */
type Case = { name: string; before: string; after: string };
const s = (value: string): string => value;   // 조건식이 상수로 접히지 않게

const CASES: Case[] = [
  ...[true, false].map((live) => ({
    name: `macro.overview live=${live}`,
    before: `/api/macro/overview${live ? "?live=true" : ""}`,
    after: endpoints.macro.overview(live),
  })),
  ...[["usdkrw", "dxy"], []].map((selected) => ({
    name: `macro.fx ${selected.length}개`,
    before: `/api/macro/fx?ids=${selected.join(",")}&period=${"5y"}&mode=${"index"}`,
    after: endpoints.macro.fx(selected, "5y", "index"),
  })),
  { name: "macro.fred", before: `/api/macro/fred/${"T10Y2Y"}?years=10`, after: endpoints.macro.fred("T10Y2Y", 10) },
  { name: "macro.ticker", before: `/api/macro/ticker?symbol=${encodeURIComponent("^VIX")}&period=${"6mo"}`,
    after: endpoints.macro.ticker("^VIX", "6mo") },
  { name: "liquidity", before: `/api/liquidity?years=${"5"}`, after: endpoints.macro.liquidity("5") },
  { name: "sector", before: `/api/sector/rotation?period=${"1M"}`, after: endpoints.macro.sectorRotation("1M") },
  { name: "13f.portfolio", before: `/api/sec13f/portfolio?cik=${"0001067983"}&quarters=${"8"}&topN=${"15"}`,
    after: endpoints.institution.portfolio("0001067983", "8", "15") },
  ...[[s("0001067983,0001336528"), s("2026-06-30")], [s(""), s("")]].map(([ciks, reportDate]) => ({
    name: `13f.consensus ciks=${ciks || "없음"}`,
    before: `/api/sec13f/consensus?minHolders=${"3"}&topN=40${
      ciks ? `&ciks=${ciks}` : ""
    }${reportDate ? `&reportDate=${reportDate}` : ""}`,
    after: endpoints.institution.consensus({ minHolders: "3", topN: 40, ciks, reportDate }),
  })),
  ...[s("2026-06-30"), s("")].map((reportDate) => ({
    name: `13f.newBuys reportDate=${reportDate || "없음"}`,
    before: `/api/sec13f/new-buys?minHolders=${"2"}` +
      (reportDate ? `&reportDate=${encodeURIComponent(reportDate)}` : ""),
    after: endpoints.institution.newBuys("2", reportDate),
  })),
  { name: "guru.holders", before: `/api/guru/holders?q=${encodeURIComponent("APPLE INC")}`,
    after: endpoints.institution.guruHolders("APPLE INC") },
  { name: "guru.risk", before: `/api/guru/risk?cik=${"0001067983"}&benchmark=${"SPY"}&years=${"1"}`,
    after: endpoints.institution.guruRisk("0001067983", "SPY", "1") },
  { name: "scorecard", before: `/api/stock/scorecard?symbol=${"AAPL"}&benchmark=${"SPY"}&years=${"1"}`,
    after: endpoints.insight.scorecard("AAPL", "SPY", "1") },
  { name: "correlation",
    before: `/api/analytics/correlation?x=${encodeURIComponent("liquidity:net")}&y=${encodeURIComponent("etf:QQQ")}` +
      `&window=${"60"}&years=${"5"}&mode=${"change"}`,
    after: endpoints.insight.correlation({ x: "liquidity:net", y: "etf:QQQ", window: "60", years: "5", mode: "change" }) },
  { name: "regime", before: `/api/analytics/regime?years=${"5"}`, after: endpoints.insight.regime("5") },
  { name: "cot.asset", before: `/api/cot/asset?name=${encodeURIComponent("S&P 500 E-Mini")}`,
    after: endpoints.positioning.cotAsset("S&P 500 E-Mini") },
  { name: "cot.extremes",
    before: `/api/cot/extremes?name=${encodeURIComponent("S&P 500 E-Mini")}` + `&percentile=${"90"}&lookbackWeeks=${"156"}`,
    after: endpoints.positioning.cotExtremes("S&P 500 E-Mini", "90", "156") },
  { name: "krx.futures", before: "/api/krx/futures?days=60", after: endpoints.positioning.krxFutures(60) },
  { name: "krx.intraday", before: "/api/krx/intraday?minutes=30", after: endpoints.positioning.krxIntraday(30) },
  { name: "radar.ranking",
    before: `/api/radar/ranking?market=${"KOSPI"}&investor=${encodeURIComponent("외국인")}` +
      `&tradeType=${encodeURIComponent("순매수")}&intervalType=${"DAY"}&topN=${"30"}`,
    after: endpoints.positioning.radarRanking({ market: "KOSPI", investor: "외국인", tradeType: "순매수", intervalType: "DAY", topN: "30" }) },
  { name: "radar.consensus",
    before: `/api/radar/consensus?market=${"KOSDAQ"}&tradeType=${encodeURIComponent("순매도")}` +
      `&intervalType=${"DAY"}&topN=${"30"}`,
    after: endpoints.positioning.radarConsensus({ market: "KOSDAQ", tradeType: "순매도", intervalType: "DAY", topN: "30" }) },
  ...[s("2026-09-25"), s("")].map((date) => ({
    name: `radar.history obsDate=${date || "없음"}`,
    before: `/api/radar/history?market=${encodeURIComponent("KOSPI")}&investor=${encodeURIComponent(
      "외국인",
    )}&tradeType=${encodeURIComponent("순매수")}&latest=true${date ? `&obsDate=${date}` : ""}`,
    after: endpoints.positioning.radarHistory({ market: "KOSPI", investor: "외국인", tradeType: "순매수", obsDate: date }),
  })),
  { name: "kr.stockFlows", before: `/api/kr/stock-flows?codes=${"005930,000660"}`,
    after: endpoints.positioning.stockFlows("005930,000660") },
  { name: "kr.fundamentals", before: `/api/kr/fundamentals?codes=${"005930,000660"}`,
    after: endpoints.publicData.fundamentals("005930,000660") },
  { name: "kr.marketTotals", before: "/api/kr/market-totals?days=180", after: endpoints.publicData.marketTotals(180) },
  { name: "publicApis", before: `/api/status/public-apis?run=${3}`, after: endpoints.publicData.apiStatus(3) },
  { name: "status.refresh", before: "/api/status/refresh?runFast=true", after: endpoints.status.refresh(true) },
  { name: "status.run", before: `/api/status/run/${"macro_collected"}`, after: endpoints.status.runTask("macro_collected") },
  { name: "status.history", before: `/api/status/history?task=${encodeURIComponent("macro_collected")}&limit=1`,
    after: endpoints.status.history("macro_collected", 1) },
  { name: "ai.test", before: `/api/ai/test?engineId=${"auto"}&prompt=${encodeURIComponent("연결 확인 & 한 문장")}`,
    after: endpoints.ai.test("auto", "연결 확인 & 한 문장") },
  { name: "toss.diagnostics", before: `/api/ai/toss/diagnostics?run=${2}`, after: endpoints.toss.diagnostics(2) },
  { name: "toss.exchangeRate", before: "/api/ai/toss/exchange-rate?base=USD&quote=KRW",
    after: endpoints.toss.exchangeRate("USD", "KRW") },
  { name: "toss.indices", before: "/api/ai/toss/indices?symbols=KOSPI,KOSDAQ", after: endpoints.toss.indices("KOSPI,KOSDAQ") },
];

describe("API 경로 — 예전 조립식과 서버가 받는 값이 같다", () => {
  it.each(CASES)("$name", ({ before, after }) => {
    expect(received(after)).toEqual(received(before));
  });
});

describe("값 인코딩", () => {
  it("&·공백이 들어가도 파라미터가 쪼개지지 않는다", () => {
    const url = endpoints.insight.scorecard("BRK.B & CO", "S P Y", "1");
    expect(received(url).params).toEqual([["symbol", "BRK.B & CO"], ["benchmark", "S P Y"], ["years", "1"]]);
  });

  it("경로 조각도 인코딩한다", () => {
    expect(endpoints.status.runTask("a/b")).toBe("/api/status/run/a%2Fb");
  });
});

/** endpoints의 잎(문자열 경로·경로를 만드는 함수)을 모읍니다. */
function leaves(value: unknown, out = { strings: [] as string[], functions: 0 }) {
  if (typeof value === "string") out.strings.push(value);
  else if (typeof value === "function") out.functions += 1;
  else if (value && typeof value === "object") Object.values(value).forEach((v) => leaves(v, out));
  return out;
}

/** 쿼리를 뗀 경로. 동적 조각은 문서 표기로 바꿉니다. */
const documentedForm = (url: string) => new URL(url, "http://backend").pathname
  .replace(/^\/api\/macro\/fred\/[^/]+$/, "/api/macro/fred/{seriesId}")
  .replace(/^\/api\/status\/run\/[^/]+$/, "/api/status/run/{taskName}");

describe("docs/API.md 백엔드 표와 대조", () => {
  it("화면이 부르는 경로는 모두 문서(= 백엔드 경로 목록 테스트가 대조하는 표)에 있다", () => {
    const doc = readFileSync(resolve(__dirname, "../../../../docs/API.md"), "utf8");
    // 표 형식 두 가지: `GET /api/x` 또는 | GET | `/api/x` |
    const documented = new Set([...doc.matchAll(/`(?:(?:GET|POST) )?(\/api\/[^`?\s]*)/g)].map((m) => m[1]));
    expect(documented.size).toBeGreaterThan(50);

    const { strings, functions } = leaves(endpoints);
    const paths = [...strings, ...CASES.map(({ after }) => after)].map(documentedForm);
    expect(paths.filter((path) => !documented.has(path))).toEqual([]);
    // 경로를 만드는 함수는 모두 위 CASES에 한 줄 이상 있습니다. 새 함수를 만들었다면 CASES에 추가하고 이 숫자를 고치세요.
    expect(functions).toBe(32);
  });
});
