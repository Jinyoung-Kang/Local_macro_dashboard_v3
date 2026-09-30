"use client";

import { useState } from "react";
import { CopyButton } from "@/components/CopyButton";
import { Banner, Button, Card, Loading, Select, SourceBadge } from "@/components/ui";
import { useApi } from "@/hooks/useApi";
import { useAsyncAction } from "@/hooks/useAsyncAction";
import { useElapsedSeconds } from "@/hooks/useElapsedSeconds";
import { apiPost } from "@/lib/api";
import { formatKst } from "@/lib/format";
import type { AiEngines, AiResponse, SnapshotText } from "@/lib/types";
import { SOURCES } from "@/lib/sources";
import { endpoints } from "@/lib/endpoints";

/**
 * 🤖 AI 종합 데이터 분석 &amp; 결론 리포트.
 *
 * 프롬프트에는 대시보드가 수집한 <b>원본 텍스트</b>가 그대로 들어갑니다.
 * 추정치·대용 지표에는 경고 문구가 함께 들어가므로, AI가 실제 지표의 임계치를
 * 추정치에 적용하는 것을 막습니다.
 */
export default function AiReportPage() {
  const engines = useApi<AiEngines>(endpoints.ai.engines);
  const reportTypes = useApi<{ types: string[] }>(endpoints.ai.reportTypes);
  const snapshot = useApi<SnapshotText>(endpoints.snapshot.text);

  const [engineId, setEngineId] = useState("auto");
  const [reportType, setReportType] = useState("");
  const [extra, setExtra] = useState("");
  const [showData, setShowData] = useState(false);
  const report = useAsyncAction(
    (body: { engineId: string; reportType: string | undefined; extraInstruction: string }) =>
      apiPost<AiResponse>(endpoints.ai.report, body),
    "리포트 생성에 실패했습니다.",
  );
  const { busy, result, error } = report;
  const elapsed = useElapsedSeconds(busy);

  const selectedEngine = (engines.data?.engines ?? []).find(
    (engine) => engine.id === engineId,
  );
  const autoBudget = engines.data?.autoBudgetSeconds;
  const engineTimeout = engines.data?.timeoutSeconds;

  const generate = () =>
    report.run({ engineId, reportType: reportType || reportTypes.data?.types?.[0], extraInstruction: extra });

  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="text-xl font-bold text-bright">🤖 AI 종합 데이터 분석 &amp; 결론 리포트</h1>
        <p className="mt-1 text-xs text-muted">
          수집된 데이터만 근거로 삼습니다. 데이터에 없는 수치는 생성하지 않도록 지시합니다.
        </p>
      </header>

      {engines.data && !engines.data.enabled && (
        <Banner tone="warn">
          AI 키가 하나도 설정되지 않았습니다. NVIDIA / Cerebras / Cloudflare 중 하나 이상을
          설정하면 이 메뉴가 활성화됩니다.
        </Banner>
      )}

      <Card title="리포트 설정">
        <div className="flex flex-wrap items-end gap-3">
          <Select
            label="AI 엔진"
            value={engineId}
            onChange={setEngineId}
            options={(engines.data?.engines ?? []).map((engine) => ({
              value: engine.id,
              // 고르기 전에 속도를 보여 줍니다. 추론형 모델이 느린 것은 고장이
              // 아니라 그 모델의 성질인데, 목록만 보면 알 수 없었습니다.
              label: `${engine.label}${engine.speedHint ? ` · ${engine.speedHint}` : ""}${
                engine.available ? "" : " (키 없음)"
              }`,
            }))}
          />
          <Select
            label="리포트 종류"
            value={reportType || (reportTypes.data?.types?.[0] ?? "")}
            onChange={setReportType}
            options={(reportTypes.data?.types ?? []).map((type) => ({
              value: type,
              label: type,
            }))}
          />
        </div>

        <p className="mt-2 text-[11px] leading-relaxed text-muted">
          {engineId === "auto"
            ? `⚡ 자동 탐색은 빠른 엔진부터 부릅니다${
                autoBudget ? ` (전체 ${autoBudget}초를 넘기면 중단하고 실패 경로를 보여 줍니다)` : ""
              }.`
            : `고른 엔진만 부릅니다${
                engineTimeout ? ` (최대 ${engineTimeout}초 대기)` : ""
              }.${selectedEngine?.description ? ` ${selectedEngine.description}.` : ""}`}
        </p>

        <label className="mt-4 block text-xs text-muted">
          추가 지시 (선택)
          <textarea
            value={extra}
            onChange={(event) => setExtra(event.target.value)}
            rows={3}
            maxLength={2000}
            placeholder="예: 향후 2주 관점에서 코스피200 선물 포지션에 집중해 주세요."
            className="mt-1 w-full rounded-md border border-border bg-canvas px-3 py-2 text-sm text-body outline-none focus:border-accent"
          />
        </label>

        <div className="mt-4 flex flex-wrap items-center gap-2">
          <Button
            variant="primary"
            onClick={generate}
            disabled={busy || !engines.data?.enabled}
          >
            {busy ? `생성 중… ${elapsed}초 경과` : "🚀 리포트 생성"}
          </Button>
          <Button onClick={() => setShowData((value) => !value)}>
            {showData ? "원본 데이터 닫기" : "AI에 전달되는 원본 데이터 보기"}
          </Button>
          <CopyButton
            text={snapshot.data?.text}
            label="원본 데이터 복사"
            disabled={!snapshot.data?.text}
          />
        </div>
      </Card>

      {showData && (
        <Card
          title="📋 수집 데이터 원본 (AI 입력)" source={SOURCES.aiInput}
          subtitle={
            snapshot.data
              ? `${formatKst(snapshot.data.generatedAtKst)} · ${snapshot.data.chars.toLocaleString("ko-KR")}자`
              : undefined
          }
          actions={
            <CopyButton text={snapshot.data?.text} label="복사" disabled={!snapshot.data?.text} />
          }
        >
          {snapshot.loading && !snapshot.data && <Loading />}
          <pre className="max-h-[480px] overflow-auto whitespace-pre-wrap rounded border border-border bg-canvas p-3 text-[11px] leading-relaxed text-muted">
            {snapshot.data?.text ?? ""}
          </pre>
        </Card>
      )}

      {error && <Banner tone="danger">{error}</Banner>}

      {result && (
        <Card
          title={`📄 ${result.reportType ?? "AI 리포트"}`}
          source={`${result.provider ?? "AI"}가 대시보드 저장본을 읽고 쓴 해석 — 수치의 원래 출처는 입력 원본에 표기 · 투자 판단 근거 아님`}
          subtitle={
            result.status
              ? `${result.provider} · ${formatLatency(result.latencyMs)}${
                  result.translationInfo ? ` · ${result.translationInfo}` : ""
                }`
              : undefined
          }
          actions={
            <span className="flex flex-wrap items-center gap-2">
              {result.model && <SourceBadge>{result.model}</SourceBadge>}
              {result.status && (
                <CopyButton
                  text={result.response}
                  label="리포트 복사"
                  variant="primary"
                  disabled={!result.response}
                />
              )}
              {result.originalResponse && (
                <CopyButton text={result.originalResponse} label="번역 전 원문 복사" />
              )}
            </span>
          }
        >
          {!result.status ? (
            <Banner tone="danger">
              생성 실패: {result.error}
              {result.failoverPath && (
                <ul className="mt-2 list-disc pl-5 text-xs">
                  {result.failoverPath.map((step) => (
                    <li key={step}>{step}</li>
                  ))}
                </ul>
              )}
            </Banner>
          ) : (
            <>
              {result.failoverPath && result.failoverPath.length > 1 && (
                <p className="mb-3 text-[11px] text-muted">
                  폴오버 경로: {result.failoverPath.join(" → ")}
                </p>
              )}
              <article className="whitespace-pre-wrap text-sm leading-relaxed text-body">
                {result.response}
              </article>
            </>
          )}
        </Card>
      )}
    </div>
  );
}

/** 밀리초를 사람이 읽는 시간으로. 3분 42초가 "222134ms"로 보이면 감이 안 옵니다. */
function formatLatency(latencyMs?: number): string {
  if (latencyMs === undefined || latencyMs === null) {
    return "소요 시간 미상";
  }
  const seconds = latencyMs / 1000;
  if (seconds < 60) {
    return `${seconds.toFixed(1)}초`;
  }
  return `${Math.floor(seconds / 60)}분 ${Math.round(seconds % 60)}초`;
}
