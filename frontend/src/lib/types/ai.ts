/**
 * 백엔드 응답 계약 — AI 리포트.
 *
 * 값이 없을 수 있는 필드는 전부 `| null`입니다. 프런트는 그 null을 그대로 "—"로 표시하고,
 * 절대 0으로 바꾸지 않습니다. 가져올 때는 `@/lib/types`(배럴)를 그대로 쓰면 됩니다.
 */
export interface AiEngine {
  id: string;
  label: string;
  provider: string;
  model: string | null;
  description: string;
  /** 예상 응답 속도 — 추론형 모델이 왜 느린지 고르기 전에 알려 줍니다. */
  speedHint?: string;
  available: boolean;
}

export interface AiEngines {
  engines: AiEngine[];
  enabled: boolean;
  /** 엔진을 직접 골랐을 때의 대기 한도(초). */
  timeoutSeconds?: number;
  /** 자동 탐색에서 엔진 하나를 기다리는 한도(초). */
  autoAttemptSeconds?: number;
  /** 자동 탐색 전체 시간 예산(초). */
  autoBudgetSeconds?: number;
}

/** 📋 전체 대시보드 원본 데이터 (AI 분석 없음). */
export interface SnapshotText {
  text: string;
  generatedAtKst: string;
  chars: number;
  lineCount: number;
  sections: string[];
}

export interface AiResponse {
  status: boolean;
  provider?: string;
  model?: string;
  response?: string;
  error?: string | null;
  latencyMs?: number;
  pipelineStep?: string;
  failoverPath?: string[];
  translationInfo?: string;
  originalResponse?: string;
  reportType?: string;
}
