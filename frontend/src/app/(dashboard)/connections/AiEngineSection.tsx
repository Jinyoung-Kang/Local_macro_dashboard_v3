"use client";

import { useState } from "react";
import { Banner, Button, Card, Loading, Select, SourceBadge, Table } from "@/components/ui";
import { useApi } from "@/hooks/useApi";
import { errorMessage, useAsyncAction } from "@/hooks/useAsyncAction";
import { apiPost } from "@/lib/api";
import type { AiEngine, AiResponse } from "@/lib/types";
import { SOURCES } from "@/lib/sources";
import { endpoints } from "@/lib/endpoints";

const SAMPLE_PROMPTS = [
  "한국어로 한 문장만 답하십시오: 지금 연결이 정상인지 알려 주세요.",
  "장단기 금리차 역전이 시장에 주는 의미를 3줄로 요약해 주세요.",
  "미결제약정이 늘면서 가격이 하락하는 국면을 한 문장으로 설명해 주세요.",
];

/** 🤖 AI 엔진 — 엔진별 키 설정 여부, 호출 응답·지연시간·폴오버 경로. */
export function AiEngineSection() {
  const engines = useApi<{ engines: AiEngine[]; keys: Record<string, boolean>; enabled: boolean }>(
    endpoints.ai.engines,
  );
  const [engineId, setEngineId] = useState("auto");
  const [prompt, setPrompt] = useState(SAMPLE_PROMPTS[0]);
  // 실패도 결과 자리(빨간 안내)에 보여 줍니다 — 오류를 결과 모양으로 바꿔 돌려줍니다.
  const { run: call, busy, result } = useAsyncAction(
    (id: string, text: string): Promise<AiResponse> =>
      apiPost<AiResponse>(endpoints.ai.test(id, text))
        .catch((error: unknown) => ({ status: false, error: errorMessage(error, "호출에 실패했습니다.") })),
    "호출에 실패했습니다.",
  );
  const run = () => call(engineId, prompt);

  return (
    <section className="flex flex-col gap-4">
      <h2 className="text-base font-semibold text-bright">🤖 AI 엔진</h2>

      {engines.loading && !engines.data && <Loading />}

      {engines.data && (
        <Card title="등록된 엔진" source={SOURCES.aiEngines} subtitle="엔진별 응답·지연시간·자동 번역 동작을 확인합니다">
          <Table
            rows={engines.data.engines}
            rowKey={(row) => row.id}
            columns={[
              { key: "label", header: "엔진", render: (row) => row.label },
              {
                key: "model",
                header: "모델",
                render: (row) => (row.model ? <SourceBadge>{row.model}</SourceBadge> : "—"),
              },
              { key: "description", header: "용도", render: (row) => row.description },
              {
                key: "available",
                header: "키",
                render: (row) => (
                  <span className={row.available ? "text-ok" : "text-muted"}>
                    {row.available ? "설정됨" : "없음"}
                  </span>
                ),
              },
            ]}
          />
        </Card>
      )}

      <Card title="호출 테스트">
        <div className="flex flex-wrap items-end gap-3">
          <Select
            label="엔진"
            value={engineId}
            onChange={setEngineId}
            options={(engines.data?.engines ?? []).map((engine) => ({
              value: engine.id,
              label: engine.label,
            }))}
          />
          <Select
            label="추천 프롬프트"
            value={prompt}
            onChange={setPrompt}
            options={SAMPLE_PROMPTS.map((value) => ({
              value,
              label: value.length > 30 ? `${value.slice(0, 30)}…` : value,
            }))}
          />
        </div>

        <textarea
          aria-label="호출 테스트에 보낼 프롬프트"
          value={prompt}
          onChange={(event) => setPrompt(event.target.value)}
          rows={3}
          maxLength={2000}
          className="mt-3 w-full rounded-md border border-border bg-canvas px-3 py-2 text-sm text-body outline-none focus:border-accent"
        />

        <div className="mt-3">
          <Button variant="primary" onClick={run} disabled={busy || !engines.data?.enabled}>
            {busy ? "호출 중…" : "🧪 선택 모델 호출"}
          </Button>
        </div>
      </Card>

      {result && (
        <Card
          title="응답" source={SOURCES.aiEngines}
          subtitle={
            result.status
              ? `${result.provider} · ${result.latencyMs}ms · ${result.pipelineStep}`
              : result.pipelineStep
          }
        >
          {result.status ? (
            <article className="whitespace-pre-wrap text-sm leading-relaxed text-body">
              {result.response}
            </article>
          ) : (
            <Banner tone="danger">{result.error}</Banner>
          )}
          {result.failoverPath && (
            <p className="mt-3 text-[11px] text-muted">
              폴오버 경로: {result.failoverPath.join(" → ")}
            </p>
          )}
          {result.translationInfo && (
            <p className="mt-1 text-[11px] text-muted">{result.translationInfo}</p>
          )}
        </Card>
      )}
    </section>
  );
}
