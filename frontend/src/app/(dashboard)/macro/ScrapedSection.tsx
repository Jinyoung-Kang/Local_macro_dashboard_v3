"use client";

import {
  Card,
  Loading,
  SourceBadge,
  Table,
} from "@/components/ui";
import { useApi } from "@/hooks/useApi";
import {
  deltaColor,
  EMPTY,
  formatKst,
  formatNumber,
  formatPercent,
} from "@/lib/format";
import { endpoints } from "@/lib/endpoints";

export function ScrapedSection() {
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
  }>(endpoints.macro.scraped, 120_000);

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
