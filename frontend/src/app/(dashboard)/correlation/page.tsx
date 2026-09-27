"use client";

import { useState } from "react";
import { LineSeries, ScatterPlot, SERIES_COLORS } from "@/components/charts";
import {
  Banner,
  Card,
  EmptyState,
  ErrorState,
  Loading,
  Metric,
  Select,
  SourceBadge,
} from "@/components/ui";
import { useApi } from "@/hooks/useApi";
import { EMPTY, formatNumber } from "@/lib/format";
import type { CorrelationResponse, SeriesRef } from "@/lib/types";
import { SOURCES } from "@/lib/sources";

/**
 * 🔗 지표 상관관계.
 *
 * <p>두 지표가 <b>같이 움직이는지</b> 봅니다. 기본값은 "변화(Δ)"입니다 — 수준
 * 끼리 비교하면 둘 다 추세만 가져도 상관이 0.9를 넘습니다(허위 상관).
 *
 * <p>상관은 <b>인과가 아닙니다.</b> 화면은 계산 결과와 함께 표본 수와 주의
 * 문구를 항상 같이 보여 줍니다.
 */
export default function CorrelationPage() {
  const catalog = useApi<{ series: SeriesRef[] }>("/api/analytics/series");

  const [x, setX] = useState("liquidity:net");
  const [y, setY] = useState("etf:QQQ");
  const [window, setWindow] = useState("60");
  const [years, setYears] = useState("3");
  const [mode, setMode] = useState("change");

  const { data, loading, error, reload } = useApi<CorrelationResponse>(
    `/api/analytics/correlation?x=${encodeURIComponent(x)}&y=${encodeURIComponent(y)}` +
      `&window=${window}&years=${years}&mode=${mode}`,
    300_000,
  );

  const options = (catalog.data?.series ?? []).map((entry) => ({
    value: entry.id,
    label: `${entry.label} · ${entry.group}`,
  }));

  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="text-xl font-bold text-bright">🔗 지표 상관관계</h1>
        <p className="mt-1 text-xs text-muted">
          이미 수집한 지표끼리 엮어 봅니다 · 롤링 상관계수 + 산점도 · 상관은 인과가 아닙니다
        </p>
      </header>

      <Card title="비교할 지표">
        <div className="flex flex-wrap items-end gap-3">
          <Select label="지표 X" value={x} onChange={setX} options={options} />
          <Select label="지표 Y" value={y} onChange={setY} options={options} />
          <Select
            label="비교 방식"
            value={mode}
            onChange={setMode}
            options={[
              { value: "change", label: "변화끼리 (권장)" },
              { value: "level", label: "수준끼리 (허위 상관 주의)" },
            ]}
          />
          <Select
            label="롤링 창"
            value={window}
            onChange={setWindow}
            options={["20", "60", "120", "250"].map((value) => ({
              value,
              label: `${value} 관측`,
            }))}
          />
          <Select
            label="조회 기간"
            value={years}
            onChange={setYears}
            options={["1", "3", "5", "10"].map((value) => ({ value, label: `최근 ${value}년` }))}
          />
        </div>
      </Card>

      {loading && !data && <Loading label="상관을 계산하는 중…" />}
      {error && <ErrorState message={error} onRetry={reload} />}

      {data && !data.available && (
        <Banner tone="warn">{data.message ?? "계산할 수 없습니다."}</Banner>
      )}

      {data?.available && (
        <>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <Metric
              label={`상관계수 (${mode === "change" ? "변화" : "수준"})`}
              value={data.overall === null ? EMPTY : formatNumber(data.overall, 3)}
              caption={strength(data.overall)}
            />
            <Metric
              label="표본 수"
              value={`${data.samples.toLocaleString("ko-KR")} 개`}
              caption="두 지표가 모두 값을 가진 날짜만 셉니다."
            />
            <Metric label="기간 시작" value={data.firstDate ?? EMPTY} />
            <Metric label="기간 끝" value={data.lastDate ?? EMPTY} />
          </div>

          {(data.notes ?? []).map((note) => (
            <p key={note} className="rounded border border-border bg-canvas px-3 py-2 text-xs text-muted">
              ⚠️ {note}
            </p>
          ))}

          <Card
            title={`📈 롤링 상관계수 (${window} 관측)`} source={`${data.x?.label}: ${data.x?.source} · ${data.y?.label}: ${data.y?.source}`}
            subtitle="한 숫자로 요약한 상관은 시기에 따라 크게 달라집니다. 언제 함께 움직였는지를 봅니다."
            actions={
              <SourceBadge>
                {data.x?.label} ↔ {data.y?.label}
              </SourceBadge>
            }
          >
            <LineSeries
              data={(data.rolling ?? []).map((point) => ({
                date: point.date,
                value: point.value,
              }))}
              zeroLine
              precision={2}
              color={SERIES_COLORS.green}
              height={260}
            />
          </Card>

          <Card
            title="🔵 산점도" source={`${data.x?.label}: ${data.x?.source} · ${data.y?.label}: ${data.y?.source}`}
            subtitle={
              mode === "change"
                ? "점 하나가 하루(또는 한 관측)의 변화입니다. 오른쪽 위·왼쪽 아래에 몰리면 같이 움직인 것입니다."
                : "수준끼리 뿌린 점입니다. 시간에 따라 이동한 궤적이 대각선으로 보이면 추세 때문입니다."
            }
          >
            <ScatterPlot
              data={data.scatter ?? []}
              xLabel={data.x?.label ?? "X"}
              yLabel={data.y?.label ?? "Y"}
              xUnit={data.x?.unit ?? ""}
              yUnit={data.y?.unit ?? ""}
              height={360}
            />
          </Card>
        </>
      )}

      {!loading && !data && <EmptyState message="지표를 고르면 계산합니다." />}
    </div>
  );
}

/** 상관계수의 세기를 말로 옮깁니다. 숫자만 보면 0.35가 큰지 작은지 알기 어렵습니다. */
function strength(value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return "계산할 수 없습니다.";
  }
  const magnitude = Math.abs(value);
  const direction = value > 0 ? "같은 방향" : "반대 방향";
  if (magnitude < 0.2) {
    return "거의 무관합니다.";
  }
  if (magnitude < 0.4) {
    return `약하게 ${direction}으로 움직였습니다.`;
  }
  if (magnitude < 0.6) {
    return `어느 정도 ${direction}으로 움직였습니다.`;
  }
  return `뚜렷하게 ${direction}으로 움직였습니다.`;
}
