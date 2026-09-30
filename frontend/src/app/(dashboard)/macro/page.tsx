"use client";

import {
  AutoRefreshControl,
  LIVE_THRESHOLD_SECONDS,
  useAutoRefreshSeconds,
} from "@/components/AutoRefresh";
import { RawSnapshotCard } from "@/components/RawSnapshotCard";
import { SessionBadge, useKrOfficialHolidays, useMinuteClock } from "@/components/SessionBadge";
import {
  Banner,
  Card,
  ErrorState,
  Freshness,
  Loading,
  Metric,
  SourceBadge,
  Table,
} from "@/components/ui";
import { useApi } from "@/hooks/useApi";
import { SOURCES, uniqueSources } from "@/lib/sources";
import { EMPTY, formatKst } from "@/lib/format";
import type {
  AdvancedIndicators,
  MacroItem,
  MacroOverview,
  RiskIndicators,
} from "@/lib/types";
import { FxCompareSection } from "./FxCompareSection";
import { SpreadSection } from "./SpreadSection";
import { RiskSection } from "./RiskSection";
import { AdvancedSection } from "./AdvancedSection";
import { SingleChartSection } from "./SingleChartSection";
import { ScrapedSection } from "./ScrapedSection";
import { endpoints } from "@/lib/endpoints";

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
    endpoints.macro.overview(live),
    refreshMs,
  );
  const risk = useApi<RiskIndicators>(endpoints.macro.risk, slower(120_000));
  const advanced = useApi<AdvancedIndicators>(endpoints.macro.advanced, slower(300_000));
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

/** 카드 한 장의 출처. 수집기가 붙인 값이 우선이고, 예전 저장본(출처 필드 없음)은 티커로 채웁니다. */
function itemSource(item: MacroItem): string | undefined {
  if (item.source) return item.source;
  return item.ticker ? `Yahoo Finance (${item.ticker})` : undefined;
}
