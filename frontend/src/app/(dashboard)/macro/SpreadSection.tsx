"use client";

import { useState } from "react";
import { LineSeries, SERIES_COLORS } from "@/components/charts";
import { RangeTabs, sliceByRange, type RangeValue } from "@/components/RangeTabs";
import { Banner, Card, Metric } from "@/components/ui";
import { SOURCES } from "@/lib/sources";
import { EMPTY, formatNumber, formatSigned } from "@/lib/format";
import type { MacroOverview } from "@/lib/types";

export function SpreadSection({
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
