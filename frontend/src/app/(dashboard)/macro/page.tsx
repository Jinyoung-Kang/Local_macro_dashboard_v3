"use client";

import { useState } from "react";
import { LineSeries, MultiLineSeries, SERIES_COLORS } from "@/components/charts";
import {
  AutoRefreshControl,
  LIVE_THRESHOLD_SECONDS,
  useAutoRefreshSeconds,
} from "@/components/AutoRefresh";
import { RangeTabs, sliceByRange, type RangeValue } from "@/components/RangeTabs";
import { RawSnapshotCard } from "@/components/RawSnapshotCard";
import { SessionBadge, useKrOfficialHolidays, useMinuteClock } from "@/components/SessionBadge";
import {
  Banner,
  Card,
  EmptyState,
  ErrorState,
  Freshness,
  Loading,
  Metric,
  Select,
  SourceBadge,
  Table,
} from "@/components/ui";
import { useApi } from "@/hooks/useApi";
import { SOURCES, uniqueSources } from "@/lib/sources";
import { deltaColor, EMPTY, formatKst, formatNumber, formatPercent, formatSigned, statusColor } from "@/lib/format";
import type {
  AdvancedIndicators,
  FxSeriesResponse,
  MacroItem,
  MacroOverview,
  RiskEntry,
  RiskIndicators,
} from "@/lib/types";

const SPREAD_TABLE = [
  {
    state: "정상 (Normal)",
    value: "양수 (+)",
    reading: "장기 미래의 불확실성(프리미엄)으로 장기 금리가 더 높습니다.",
    outcome: "경제의 점진적인 성장 및 확장",
  },
  {
    state: "평탄화 (Flattening)",
    value: "0에 수렴",
    reading: "미래 경기 성장이 둔화될 것이라는 우려가 커지기 시작합니다.",
    outcome: "경기 정점 통과 및 둔화 신호",
  },
  {
    state: "역전 (Inversion) ⚠️",
    value: "음수 (−)",
    reading: "현재 인플레이션을 잡기 위해 금리를 올렸으나 미래 경기 침체를 확신합니다.",
    outcome: "역사적으로 1~2년 내 경기 침체 도래",
  },
];

const RISK_TABLE = [
  {
    name: "CBOE VIX [15분 지연]",
    normal: "15 ~ 20 (15 미만: 과도한 낙관)",
    danger: "30 이상 (패닉 / 급락 / 투매)",
    note: "주식 시장의 단기 공포 측정기. 급등 시 주가 급락·투매 신호.",
  },
  {
    name: "ICE BofA MOVE [지연/마감]",
    normal: "80 ~ 120 (80 미만: 금리 초안정)",
    danger: "140 이상 (채권 발작 / 긴축 충격)",
    note: "채권 시장의 공포 지수. ⚠️ 이 화면의 값은 추정치이므로 이 임계치를 그대로 적용하지 마세요.",
  },
  {
    name: "하이일드 스프레드 [1일 지연]",
    normal: "3.5% ~ 5.0%",
    danger: "7.0% 이상 (본격 신용경색)",
    note: "한계 기업 부도 리스크 프리미엄. 침체 진입 시 가장 먼저 급등하는 선행 지표.",
  },
  {
    name: "3M 금융 CP 스프레드 [1일 지연]",
    normal: "0.20%p ~ 0.50%p",
    danger: "0.80%p 이상 (단기 자금시장 경색)",
    note: "은행권 3개월 단기 자금조달 가산금리(현대판 TED 스프레드).",
  },
  {
    name: "STLFSI4 금융스트레스 [주간]",
    normal: "0.0 이하 (장기 평균)",
    danger: "+1.0 이상 (시스템 위기 경보)",
    note: "18개 금융시장 지표를 종합한 복합 척도.",
  },
];

export default function MacroPage() {
  const [refreshSeconds, setRefreshSeconds] = useAutoRefreshSeconds();
  const refreshMs = refreshSeconds * 1000;

  // 1분 이하를 고르면 백엔드에 live로 요청합니다. 저장본을 다시 받을 기준이
  // 15분에서 60초로 내려가, 카드 숫자가 실제로 움직입니다.
  // (60초보다 더 줄이지 않는 이유 — Yahoo 429. Datasets.MAX_AGE_LIVE 참고)
  const live = refreshSeconds > 0 && refreshSeconds <= LIVE_THRESHOLD_SECONDS;

  // 카드 시세만 빠르게 읽습니다.
  //
  // 리스크 지표는 일별 확정치(FRED·변동성 저장본)이고 심화 지표 6종은 대부분
  // 주·월 단위로 갱신됩니다. 10초마다 다시 읽어 봐야 같은 값이라, 기존 주기
  // (2분·5분)보다 빨라지지 않게 막아 둡니다. 느리게 고르는 것은 그대로 따릅니다.
  const slower = (floorMs: number) =>
    refreshMs === 0 ? 0 : Math.max(refreshMs, floorMs);

  const overview = useApi<MacroOverview>(
    `/api/macro/overview${live ? "?live=true" : ""}`,
    refreshMs,
  );
  const risk = useApi<RiskIndicators>("/api/macro/risk", slower(120_000));
  const advanced = useApi<AdvancedIndicators>("/api/macro/advanced", slower(300_000));
  // 개장/마감 배지는 보는 시각 기준이라 1분마다 다시 판정합니다(데이터는 다시 받지 않음).
  const now = useMinuteClock();
  const krOfficial = useKrOfficialHolidays(now);

  if (overview.loading && !overview.data) {
    return <Loading label="매크로 지표를 불러오는 중…" />;
  }
  if (overview.error) {
    return <ErrorState message={overview.error} onRetry={overview.reload} />;
  }

  const data = overview.data;

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-bright">📊 거시경제 매크로 지표</h1>
          <p className="mt-1 text-xs text-muted">
            환율·국채·원자재·지수, 장단기 금리차, 신용 리스크, 심화 지표 6종
          </p>
          {/* 고른 간격보다 값이 늦게 바뀌는 이유를 화면에서 바로 알 수 있게 적습니다.
              적지 않으면 "10초로 해 뒀는데 숫자가 그대로"로 읽힙니다. */}
          {live && (
            <p className="mt-1 text-[11px] text-muted">
              카드 시세는 최대 1분마다 새로 수집됩니다. 출처(Yahoo·TradingView)가
              폴링·지연 시세라 그보다 빠르게는 바뀌지 않습니다.
            </p>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <AutoRefreshControl
            seconds={refreshSeconds}
            onChange={setRefreshSeconds}
            loadedAt={overview.loadedAt}
            loading={overview.loading}
          />
          <Freshness
            collectedAt={data?.collectedAtKst}
            ageSeconds={data?.ageSeconds}
            stale={data?.stale}
          />
        </div>
      </header>

      {/* 상단에 둡니다 — 화면을 스크롤하며 눈으로 옮겨 적지 않아도 되도록,
          "지금 이 대시보드가 들고 있는 값 전부"를 먼저 꺼낼 수 있게 합니다. */}
      <RawSnapshotCard />

      {!data?.available && (
        <Banner tone="warn">
          {data?.message ?? "매크로 데이터가 없습니다. 수집기를 실행하세요."}
        </Banner>
      )}

      {data?.categories?.map((category) => (
        <Card
          key={category.id}
          title={category.title}
          subtitle={category.note ? `데이터 지연: ${category.note}` : undefined}
          source={uniqueSources(category.items.map((item) => itemSource(item))) || undefined}
        >
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            {category.items.map((item) => (
              <Metric
                key={item.key}
                label={
                  // 이름과 배지를 한 줄에 두면 이름이 긴 카드(엔/원 100엔당)만 배지가
                  // 다음 줄로 밀려, 같은 줄 카드끼리 값의 높이가 어긋났습니다.
                  // 모든 카드를 "이름 줄 + 배지 줄"로 같은 구조로 둡니다.
                  <span className="flex flex-col gap-1">
                    <span>{item.name}</span>
                    <span className="flex min-h-5 flex-wrap items-center gap-1">
                      {item.note && <SourceBadge>{item.note}</SourceBadge>}
                      <SessionBadge
                        market={item.market}
                        now={now}
                        krOfficial={krOfficial}
                        lastTs={item.lastTs}
                        collectedAt={data?.collectedAtKst}
                      />
                    </span>
                  </span>
                }
                value={item.status === "fail" ? "수집 실패" : item.priceStr ?? EMPTY}
                delta={item.delta}
                deltaText={item.deltaStr ?? EMPTY}
                tone={item.status === "fail" ? "text-muted" : undefined}
                source={itemSource(item)}
                caption={
                  <span className="flex flex-col gap-0.5">
                    <span>직전: {item.prevStr ?? EMPTY}</span>
                    {item.lastTs && <span>{formatKst(item.lastTs)}</span>}
                    {item.prevSource && <span>전일값 출처: {item.prevSource}</span>}
                  </span>
                }
              />
            ))}
          </div>
        </Card>
      ))}

      {/* 환율 카드 바로 아래에 둡니다 — 위에서 "지금 얼마인지"를 보고,
          여기서 "어디서 왔는지"를 봅니다. */}
      <FxCompareSection />

      {data?.spreads && (
        <>
          <SpreadSection
            title="📊 10Y−2Y 장단기 금리차"
            block={data.spreads.official10y2y}
          />
          <SpreadSection
            title="📊 30Y−2Y 장단기 금리차"
            block={data.spreads.official30y2y}
          />
          <Card
            title="📖 장단기 금리차 해석 기준"
            subtitle="역사적 분포에 근거한 참고치이며 투자 판단의 근거가 아닙니다."
            source={SOURCES.internalRules}
          >
            <Table
              rows={SPREAD_TABLE}
              rowKey={(row) => row.state}
              columns={[
                { key: "state", header: "시장 상태", render: (row) => row.state },
                { key: "value", header: "스프레드", render: (row) => row.value },
                { key: "reading", header: "시장의 심리 및 해석", render: (row) => row.reading },
                { key: "outcome", header: "경제적 귀결", render: (row) => row.outcome },
              ]}
            />
          </Card>
        </>
      )}

      <RiskSection risk={risk.data} loading={risk.loading} />
      <AdvancedSection data={advanced.data} loading={advanced.loading} />
      <SingleChartSection />
      <ScrapedSection />
    </div>
  );
}

/**
 * 💱 환율·달러인덱스 비교.
 *
 * <p><b>왜 기본이 "기준일 = 100"인가</b> — 원/달러(약 1,380)·달러/엔(약 150)·
 * 엔/원(약 930)·달러 인덱스(약 100)를 원래 단위로 한 축에 겹치면 축이
 * 0~1,400이 되고, 아래 둘은 바닥에 눌려 직선이 됩니다. 기준일을 100으로
 * 맞추면 축이 "기준일 대비 %"가 되어 <b>무엇이 더 많이 움직였는지</b>를
 * 비교할 수 있습니다. 원래 단위도 고를 수 있지만, 단위가 섞이면 무슨 일이
 * 일어나는지 화면이 먼저 말해 줍니다.
 */
function FxCompareSection() {
  // 기본은 원/달러 + 달러 인덱스입니다. 넷을 다 켜 두면 처음 보는 사람에게
  // 선이 너무 많고, 무엇을 비교하려던 것인지 화면이 말해 주지 못합니다.
  const [selected, setSelected] = useState<string[]>(["usdkrw", "dxy"]);
  const [period, setPeriod] = useState("1y");
  const [mode, setMode] = useState<"index" | "raw">("index");

  const { data, loading, error, reload } = useApi<FxSeriesResponse>(
    `/api/macro/fx?ids=${selected.join(",")}&period=${period}&mode=${mode}`,
    600_000,
  );

  const catalog = data?.catalog ?? [];
  const series = (data?.series ?? []).filter((entry) => entry.available);

  const toggle = (id: string) => {
    setSelected((previous) =>
      previous.includes(id)
        ? // 마지막 하나는 끄지 않습니다. 전부 끄면 백엔드가 기본 선택으로
          // 되돌리는데, 화면의 버튼은 전부 꺼진 상태라 선택과 차트가 어긋납니다.
          previous.length <= 1
          ? previous
          : previous.filter((entry) => entry !== id)
        : [...previous, id],
    );
  };

  // 계열마다 거래일이 다릅니다(시장별 휴일). 날짜를 합집합으로 모으고, 값이
  // 없는 날은 비워 둡니다 — 없는 거래를 선으로 이으면 실제로는 없던 흐름이
  // 생깁니다.
  const byDate = new Map<string, Record<string, string | number | null>>();
  series.forEach((entry) => {
    entry.points.forEach((point) => {
      const row = byDate.get(point.date) ?? { date: point.date };
      row[entry.id] = point.value;
      byDate.set(point.date, row);
    });
  });
  const chartData = [...byDate.values()].sort((left, right) =>
    String(left.date).localeCompare(String(right.date)),
  );

  const chartSeries = series.map((entry, index) => ({
    key: entry.id,
    name: entry.label,
    color: FX_COLORS[index % FX_COLORS.length],
  }));

  // 축 단위는 **모든 계열이 같은 단위일 때만** 붙입니다. 원과 엔이 섞인 축에
  // "원"을 달면 엔 계열까지 원으로 읽히게 됩니다. 기준일=100 모드의 축은
  // 어느 통화도 아니므로 단위가 없습니다.
  const units = [...new Set(series.map((entry) => entry.unit ?? ""))];
  const axisUnit = mode === "raw" && units.length === 1 ? units[0] : "";

  return (
    <Card
      title="💱 환율·달러인덱스 비교"
      source={`${SOURCES.yahoo} — 매크로 카드와 같은 티커`}
      subtitle={
        mode === "index"
          ? "기준일을 100으로 맞춰 겹칩니다 — 단위가 달라도 '무엇이 더 움직였는지'를 비교할 수 있습니다."
          : "원래 단위 그대로 그립니다 — 자릿수가 비슷한 계열끼리만 의미가 있습니다."
      }
      actions={
        <Freshness
          collectedAt={data?.collectedAtKst}
          ageSeconds={data?.ageSeconds}
          stale={data?.stale}
        />
      }
    >
      <div className="flex flex-wrap gap-2">
        {catalog.map((entry) => {
          const active = selected.includes(entry.id);
          return (
            <button
              key={entry.id}
              type="button"
              onClick={() => toggle(entry.id)}
              className={`rounded-md border px-3 py-1.5 text-xs transition ${
                active
                  ? "border-accent/60 bg-accent/15 text-accent"
                  : "border-border bg-surface-hover text-muted hover:text-bright"
              }`}
            >
              {entry.label}
            </button>
          );
        })}
      </div>

      <div className="mt-4 flex flex-wrap items-end gap-3">
        <Select
          label="조회 기간"
          value={period}
          onChange={setPeriod}
          options={FX_PERIODS}
        />
        <Select
          label="표시 방식"
          value={mode}
          onChange={(value) => setMode(value === "raw" ? "raw" : "index")}
          options={[
            { value: "index", label: "기준일 = 100 (비교)" },
            { value: "raw", label: "원래 단위" },
          ]}
        />
      </div>

      {/* 고른 대로 그리되, 무슨 일이 일어나는지는 숨기지 않습니다. */}
      {mode === "raw" && data?.mixedUnits && (
        <Banner tone="warn">
          ⚠️ 단위가 섞였습니다(원·엔·pt). 한 축에 겹치면 값이 작은 계열은 바닥에
          눌려 직선처럼 보입니다. 비교가 목적이라면 <b>기준일 = 100</b>을 쓰세요.
        </Banner>
      )}

      {loading && !data && <Loading label="환율 시계열을 불러오는 중…" />}
      {error && <ErrorState message={error} onRetry={reload} />}
      {data && !data.available && (
        <Banner tone="warn">
          {data.message ?? "표시할 환율 시계열이 없습니다."}
        </Banner>
      )}

      {series.length > 0 && (
        <>
          <div className="mt-3 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            {series.map((entry) => (
              <Metric
                key={entry.id}
                label={entry.label}
                // 자릿수는 위 환율 카드와 맞춥니다. 같은 값이 카드에서는
                // 100.11, 여기서는 100.110으로 보이면 둘 중 하나를 의심하게 됩니다.
                value={`${formatNumber(entry.latest, 2)}${
                  entry.unit ? ` ${entry.unit}` : ""
                }`}
                delta={entry.changePct}
                deltaText={`기간 ${formatPercent(entry.changePct)}`}
                caption={
                  entry.baseDate
                    ? `기준일 ${entry.baseDate} · 최근 ${entry.latestDate ?? EMPTY}`
                    : undefined
                }
              />
            ))}
          </div>

          <div className="mt-4">
            <MultiLineSeries
              data={chartData}
              series={chartSeries}
              unit={axisUnit}
              precision={mode === "index" ? 1 : 2}
              height={320}
            />
          </div>

          <p className="mt-2 text-[11px] leading-relaxed text-muted">
            {mode === "index"
              ? "축은 기준일 = 100입니다. 110이면 기준일보다 10% 높다는 뜻이고, 계열별 기준일은 위 카드에 적혀 있습니다 — 시장마다 휴일이 달라 하루씩 어긋날 수 있습니다."
              : "축은 원래 단위입니다. 계열마다 단위가 다르면(원·엔·pt) 같은 눈금으로 읽으면 안 됩니다."}{" "}
            출처: Yahoo Finance 일별 종가 (매크로 카드와 같은 티커). 엔/원은
            100엔당으로 환산한 값입니다.
          </p>
        </>
      )}
    </Card>
  );
}

/** 4개 계열이 서로 구분되는 색. dataviz 검증기를 통과한 조합입니다. */
const FX_COLORS = ["#58A6FF", "#D29922", "#3FB950", "#A371F7"];

const FX_PERIODS = [
  { value: "1mo", label: "최근 1개월" },
  { value: "3mo", label: "최근 3개월" },
  { value: "6mo", label: "최근 6개월" },
  { value: "1y", label: "최근 1년" },
  { value: "2y", label: "최근 2년" },
  { value: "5y", label: "최근 5년" },
];

function SpreadSection({
  title,
  block,
}: {
  title: string;
  block: NonNullable<MacroOverview["spreads"]>["official10y2y"];
}) {
  const [range, setRange] = useState<RangeValue>("10y");

  const allPoints = block?.points ?? [];
  const points = sliceByRange(allPoints, range);
  const scraped = block?.scraped;
  const inverted = (block?.latest ?? 0) < 0;
  const pair = `${block?.longId?.replace("DGS", "")}Y−${block?.shortId?.replace("DGS", "")}Y`;

  // 카드가 공식·스크래핑 둘을 함께 담으므로 부제도 둘을 다 적습니다.
  // 예전 부제는 카드 전체를 "미 재무부 공식 일별 확정치"라고 선언해서,
  // 오른쪽 스크래핑 패널까지 확정치로 읽히게 했습니다.
  const subtitle =
    `공식: FRED ${block?.longId} − ${block?.shortId} (일별 확정치)` +
    " · 스크래핑: TradingView 참고 시세 (지금 시점)";

  return (
    <Card
      title={title}
      subtitle={subtitle}
      source={`${SOURCES.fred} · ${SOURCES.tradingView}`}
      actions={<RangeTabs value={range} onChange={setRange} />}
    >
      {/*
        두 값은 서로를 대체하지 않습니다 — 공식은 "확정된 어제까지",
        스크래핑은 "지금 시장". 한쪽만 보여 주면 어제 값을 지금 값으로
        읽거나 그 반대가 됩니다. 나란히 두고 출처와 성격을 함께 적습니다.
      */}
      <div className="grid gap-3 lg:grid-cols-2">
        <div className="rounded-lg border border-border bg-surface-hover/30 p-3">
          <p className="mb-2 text-[11px] font-semibold text-body">
            공식 확정치 · FRED
            <span className="ml-1 font-normal text-muted">(일별, 하루 이상 지연)</span>
          </p>
          <div className="grid gap-3 sm:grid-cols-2">
            <Metric
              label={`${pair} 스프레드`}
              value={
                block?.latest === null || block?.latest === undefined
                  ? EMPTY
                  : `${formatSigned(block.latest, 3)}%p`
              }
              delta={
                block?.latest !== null && block?.previous !== null
                  ? (block?.latest ?? 0) - (block?.previous ?? 0)
                  : null
              }
              deltaText={
                block?.latest !== null && block?.previous !== null
                  ? `${formatSigned((block?.latest ?? 0) - (block?.previous ?? 0), 3)}%p`
                  : EMPTY
              }
              caption={
                allPoints.length > 0
                  ? `기준일 ${allPoints[allPoints.length - 1].date}`
                  : undefined
              }
            />
            <Metric
              label="직전 확정치"
              value={
                block?.previous === null || block?.previous === undefined
                  ? EMPTY
                  : `${formatSigned(block.previous, 3)}%p`
              }
            />
          </div>
        </div>

        <div className="rounded-lg border border-border bg-surface-hover/30 p-3">
          <p className="mb-2 text-[11px] font-semibold text-body">
            스크래핑 참고 시세 · TradingView
            <span className="ml-1 font-normal text-muted">(지금, 공식 확정치 아님)</span>
          </p>
          {scraped ? (
            <div className="grid gap-3 sm:grid-cols-3">
              <Metric
                label={`${pair} 스프레드`}
                value={
                  scraped.spread === null
                    ? "수집 실패"
                    : `${formatSigned(scraped.spread, 3)}%p`
                }
                delta={scraped.delta}
                deltaText={
                  scraped.delta === null ? EMPTY : `${formatSigned(scraped.delta, 3)}%p`
                }
              />
              <Metric
                label={`미국채 ${block?.longId?.replace("DGS", "")}년물`}
                value={
                  scraped.longValue === null
                    ? EMPTY
                    : `${formatNumber(scraped.longValue, 3)}%`
                }
              />
              <Metric
                label={`미국채 ${block?.shortId?.replace("DGS", "")}년물`}
                value={
                  scraped.shortValue === null
                    ? EMPTY
                    : `${formatNumber(scraped.shortValue, 3)}%`
                }
              />
            </div>
          ) : (
            <p className="py-4 text-xs text-muted">
              스크래핑 시세를 받지 못했습니다. 공식 확정치만 표시합니다.
            </p>
          )}
        </div>
      </div>

      {inverted && (
        <div className="mt-4">
          <Banner tone="danger">
            현재 스프레드가 <strong>역전(음수)</strong> 상태입니다. 역사적으로 1~2년 내
            침체가 뒤따른 구간입니다.
          </Banner>
        </div>
      )}

      <div className="mt-4">
        <p className="mb-2 text-[11px] text-muted">
          아래 차트는 <strong className="text-body">공식 확정치</strong> 시계열입니다
          (스크래핑 값은 지금 시점 한 점이라 추이로 그리지 않습니다).
        </p>
        <LineSeries
          data={points.map((point) => ({ date: point.date, value: point.value }))}
          unit="%p"
          zeroLine
          color={SERIES_COLORS.blue}
          height={280}
        />
      </div>
    </Card>
  );
}

function RiskSection({ risk, loading }: { risk: RiskIndicators | null; loading: boolean }) {
  if (loading && !risk) {
    return (
      <Card title="⚡ 신용 리스크, 은행권 및 시장 변동성">
        <Loading />
      </Card>
    );
  }
  if (!risk) {
    return (
      <Card title="⚡ 신용 리스크, 은행권 및 시장 변동성">
        <EmptyState message="리스크 지표를 불러오지 못했습니다." />
      </Card>
    );
  }

  const entries: { key: keyof RiskIndicators; label: string; unit: string; digits: number }[] = [
    { key: "vix", label: "CBOE VIX (주식 변동성)", unit: "", digits: 2 },
    { key: "move", label: "MOVE (채권 변동성)", unit: "", digits: 2 },
    { key: "hyOas", label: "하이일드 스프레드 (HY OAS)", unit: "%p", digits: 2 },
    { key: "cpSpread", label: "3M 금융 CP 스프레드", unit: "%p", digits: 2 },
    { key: "stlfsi", label: "세인트루이스 연준 금융스트레스", unit: "pt", digits: 2 },
  ];

  return (
    <Card
      title="⚡ 신용 리스크, 은행권 및 시장 변동성"
      subtitle="주식·채권 변동성, 기업 부도 위험, 단기 자금경색, 종합 금융스트레스"
      source="FRED (공식) — 하이일드·CP·STLFSI4 · Yahoo Finance — ^VIX · MOVE는 ^TNX 변동성 기반 추정"
    >
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
        {entries.map((entry) => {
          const item: RiskEntry = risk[entry.key];
          return (
            <Metric
              key={entry.key}
              label={entry.label}
              value={
                item?.available
                  ? `${formatNumber(item.value ?? null, entry.digits)}${entry.unit}`
                  : "수집 실패"
              }
              delta={item?.delta}
              deltaText={
                item?.delta === null || item?.delta === undefined
                  ? EMPTY
                  : `${formatSigned(item.delta, entry.digits)}${entry.unit}`
              }
              tone={item?.available ? undefined : "text-muted"}
              caption={item?.asOf ? `기준일 ${item.asOf}` : undefined}
              source={RISK_SOURCES[entry.key]}
              note={
                item?.isProxy ? (
                  <span className="text-warn">
                    ⚠️ 실제 지표가 아닙니다 — {item.sourceLabel}
                  </span>
                ) : undefined
              }
            />
          );
        })}
      </div>

      <div className="mt-5">
        <h3 className="mb-2 text-sm font-semibold text-bright">
          📖 신용·은행권·변동성 핵심 해석 기준표
        </h3>
        <Table
          rows={RISK_TABLE}
          rowKey={(row) => row.name}
          columns={[
            { key: "name", header: "지표 (지연 수준)", render: (row) => row.name },
            { key: "normal", header: "정상 / 안정 범위", render: (row) => row.normal },
            { key: "danger", header: "위험 / 발작 임계치", render: (row) => row.danger },
            { key: "note", header: "성격 및 핵심 해석", render: (row) => row.note },
          ]}
        />
      </div>
    </Card>
  );
}

function AdvancedSection({
  data,
  loading,
}: {
  data: AdvancedIndicators | null;
  loading: boolean;
}) {
  const [selected, setSelected] = useState("T10Y3M");
  const [range, setRange] = useState<RangeValue>("10y");

  // 0선을 긋는 지표와, 0을 넘었을 때 무슨 뜻인지.
  //
  // 차트에는 0선만 그립니다. 선 하나로는 "지금 역전 상태"라는 판정까지
  // 전달되지 않으므로(색·선만으로 의미를 나르지 않습니다) 판정 문구는
  // 차트 위 배너가 맡습니다. 위 금리차 카드와 같은 방식입니다.
  const zeroLineNote = ZERO_LINE_NOTES[selected];

  // 10년치를 한 번 받아 두고 기간은 화면에서 자릅니다. 기간을 바꿀 때마다
  // 다시 부르면 FRED 호출만 늘고 반응도 느립니다.
  const series = useApi<{ available: boolean; points: { date: string; value: number }[] }>(
    `/api/macro/fred/${selected}?years=10`,
  );

  if (loading && !data) {
    return (
      <Card title="🧭 심화 매크로 지표">
        <Loading />
      </Card>
    );
  }

  const entries = data ? data.order.map((id) => data.latest[id]).filter(Boolean) : [];

  return (
    <Card
      title="🧭 심화 매크로 지표"
      source={SOURCES.fred}
      subtitle="명목금리·하이일드만으로는 보이지 않는 구조를 메우는 6종 (30Y-3M은 DGS30−DGS3MO로 계산, 나머지는 FRED 공식 시계열)"
      actions={<RangeTabs value={range} onChange={setRange} />}
    >
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
        {entries.map((entry) => (
          <Metric
            key={entry.id}
            label={entry.label}
            value={
              entry.available
                ? `${formatNumber(entry.value ?? null, entry.digits)}${entry.unit}`
                : "수집 실패"
            }
            delta={entry.delta}
            deltaText={
              entry.delta === null || entry.delta === undefined
                ? EMPTY
                : formatSigned(entry.delta, entry.digits)
            }
            tone={entry.available ? statusColor(entry.color) : "text-muted"}
            caption={
              entry.available ? (
                <span className="flex flex-col gap-0.5">
                  {entry.status && <span>상태: {entry.status}</span>}
                  {entry.percentile !== null && entry.percentile !== undefined && (
                    <span>표본 백분위 {formatNumber(entry.percentile, 1)}%</span>
                  )}
                  {entry.asOf && <span>기준일 {entry.asOf}</span>}
                </span>
              ) : undefined
            }
            note={entry.note}
            source={entry.source}
          />
        ))}
      </div>

      {data?.derived?.decomposition && (
        <p className="mt-4 rounded border border-border bg-canvas px-3 py-2 text-xs text-muted">
          {data.derived.decomposition}
          <span className="ml-2">
            (세 값의 기준 시점이 다르면 오차가 생기므로 참고용입니다.)
          </span>
        </p>
      )}

      <div className="mt-5">
        <div className="mb-3 flex flex-wrap items-end gap-3">
          <Select
            label="추이 차트 지표"
            value={selected}
            onChange={setSelected}
            options={entries.map((entry) => ({ value: entry.id, label: entry.label }))}
          />
          <p className="text-xs text-muted">
            {data?.latest[selected]?.why} · 출처: {data?.latest[selected]?.source}
          </p>
        </div>
        {zeroLineNote && crossedZero(zeroLineNote, data?.latest[selected]?.value) && (
          <div className="mb-3">
            <Banner tone={zeroLineNote.tone}>{zeroLineNote.message}</Banner>
          </div>
        )}
        <LineSeries
          data={sliceByRange(
            (series.data?.points ?? []).map((point) => ({
              date: point.date,
              value: point.value,
            })),
            range,
          )}
          unit={data?.latest[selected]?.unit ?? ""}
          zeroLine={Boolean(zeroLineNote)}
          precision={data?.latest[selected]?.digits ?? 2}
          color={SERIES_COLORS.blue}
          height={280}
        />
      </div>
    </Card>
  );
}

/**
 * 0선이 의미를 갖는 심화 지표와, 선을 넘었을 때의 판정 문구.
 *
 * `side`는 "어느 쪽으로 넘어갔을 때 경고인가"입니다.
 *   T10Y3M — 음수면 장단기 금리 역전
 *   NFCI   — 양수면 금융상황이 평균보다 긴축적
 * 여기 없는 지표는 0선도, 배너도 그리지 않습니다.
 */
const ZERO_LINE_NOTES: Record<
  string,
  { side: "below" | "above"; tone: "danger" | "warn"; message: string }
> = {
  T10Y3M: {
    side: "below",
    tone: "danger",
    message:
      "10년−3개월 스프레드가 역전(음수) 상태입니다. 역사적으로 1~2년 내 침체가 뒤따른 구간입니다.",
  },
  T30Y3M: {
    side: "below",
    tone: "danger",
    message:
      "30년−3개월 스프레드가 역전(음수) 상태입니다. 만기 축 전체가 눌려 있다는 뜻으로, "
      + "10Y-3M보다 늦게 역전되고 늦게 풀립니다.",
  },
  NFCI: {
    side: "above",
    tone: "warn",
    message:
      "금융상황지수가 0을 넘었습니다. 0이 장기 평균이므로, 지금은 평균보다 긴축적인 상태입니다.",
  },
};

/** 최신값이 경고 방향으로 0선을 넘었는지. 값이 없으면 판정하지 않습니다. */
function crossedZero(
  note: (typeof ZERO_LINE_NOTES)[string],
  value: number | null | undefined,
): boolean {
  if (typeof value !== "number" || Number.isNaN(value)) {
    return false;
  }
  return note.side === "below" ? value < 0 : value > 0;
}

const SINGLE_TICKERS = [
  { value: "^VIX", label: "CBOE VIX" },
  { value: "^MOVE", label: "MOVE (추정치)" },
  { value: "^GSPC", label: "S&P 500" },
  { value: "^NDX", label: "나스닥 100" },
  { value: "^KS11", label: "코스피" },
  { value: "^N225", label: "닛케이 225" },
  { value: "KRW=X", label: "원/달러" },
  { value: "DX-Y.NYB", label: "달러 인덱스" },
  { value: "CL=F", label: "WTI 원유" },
  { value: "GC=F", label: "금 선물" },
];

const PERIODS = ["1mo", "3mo", "6mo", "1y", "2y", "5y"];

function SingleChartSection() {
  const [symbol, setSymbol] = useState("^VIX");
  const [period, setPeriod] = useState("1y");

  const { data, loading, error } = useApi<{
    available: boolean;
    isProxy?: boolean;
    sourceLabel?: string | null;
    points: { date: string; close: number | null; value?: number | null }[];
  }>(`/api/macro/ticker?symbol=${encodeURIComponent(symbol)}&period=${period}`);

  return (
    <Card
      title="📈 지표별 기간별 단독 차트"
      source={symbol === "^MOVE" ? "Yahoo Finance ^TNX 변동성 기반 추정 (실제 ICE BofA MOVE 아님)" : `Yahoo Finance (${symbol})`}
    >
      <div className="mb-4 flex flex-wrap gap-3">
        <Select label="지표" value={symbol} onChange={setSymbol} options={SINGLE_TICKERS} />
        <Select
          label="조회 기간"
          value={period}
          onChange={setPeriod}
          options={PERIODS.map((value) => ({ value, label: value }))}
        />
      </div>

      {data?.isProxy && (
        <div className="mb-3">
          <Banner tone="warn">⚠️ {data.sourceLabel}</Banner>
        </div>
      )}

      {loading && <Loading />}
      {error && <ErrorState message={error} />}
      {!loading && !error && (
        <LineSeries
          data={(data?.points ?? []).map((point) => ({
            date: point.date.slice(0, 10),
            value: point.close ?? point.value ?? null,
          }))}
        />
      )}
    </Card>
  );
}

function ScrapedSection() {
  const { data, loading } = useApi<{
    available: boolean;
    updatedAt?: string;
    items: {
      key: string;
      name: string;
      provider: string;
      unit: string;
      status: string;
      price: number | null;
      previousClose: number | null;
      changePct: number | null;
      error: string | null;
    }[];
  }>("/api/macro/scraped", 120_000);

  return (
    <Card
      title="🔎 비공식 스크래핑 시세 비교"
      source="TradingView Scanner · Yahoo Finance 공개 엔드포인트 (비공식)"
      subtitle="TradingView·Yahoo 공개 엔드포인트에서 받은 참고 시세입니다. 공식 확정치가 아닙니다."
      actions={data?.updatedAt ? <SourceBadge>{formatKst(data.updatedAt)}</SourceBadge> : undefined}
    >
      {loading && !data && <Loading />}
      {data && (
        <Table
          rows={data.items ?? []}
          rowKey={(row) => row.key}
          columns={[
            { key: "name", header: "항목", render: (row) => row.name },
            { key: "provider", header: "출처", render: (row) => <SourceBadge>{row.provider}</SourceBadge> },
            {
              key: "price",
              header: "현재가",
              align: "right",
              render: (row) =>
                row.status === "ok" ? `${formatNumber(row.price, 3)} ${row.unit}` : "수집 실패",
            },
            {
              key: "prev",
              header: "전일 종가",
              align: "right",
              render: (row) => (row.previousClose === null ? EMPTY : formatNumber(row.previousClose, 3)),
            },
            {
              key: "changePct",
              header: "등락률",
              align: "right",
              render: (row) => (
                <span className={deltaColor(row.changePct)}>{formatPercent(row.changePct)}</span>
              ),
            },
            {
              key: "error",
              header: "비고",
              render: (row) => <span className="text-xs text-muted">{row.error ?? ""}</span>,
            },
          ]}
        />
      )}
    </Card>
  );
}

/** 카드 한 장의 출처. 수집기가 붙인 값이 우선이고, 예전 저장본(출처 필드 없음)은 티커로 채웁니다. */
function itemSource(item: MacroItem): string | undefined {
  if (item.source) return item.source;
  return item.ticker ? `Yahoo Finance (${item.ticker})` : undefined;
}

const RISK_SOURCES: Record<string, string> = {
  vix: "Yahoo Finance (^VIX)",
  move: "Yahoo Finance ^TNX 기반 추정",
  hyOas: "FRED BAMLH0A0HYM2 (ICE BofA 하이일드 OAS)",
  cpSpread: "FRED CPF3M − DGS3MO (계산)",
  stlfsi: "FRED STLFSI4",
};
