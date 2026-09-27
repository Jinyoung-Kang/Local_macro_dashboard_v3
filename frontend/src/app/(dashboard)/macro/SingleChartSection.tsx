"use client";

import { useState } from "react";
import { LineSeries } from "@/components/charts";
import {
  Banner,
  Card,
  ErrorState,
  Loading,
  Select,
} from "@/components/ui";
import { useApi } from "@/hooks/useApi";

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

export function SingleChartSection() {
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
