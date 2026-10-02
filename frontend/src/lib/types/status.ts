/**
 * 백엔드 응답 계약 — 수집 상태·검증·진단.
 *
 * 값이 없을 수 있는 필드는 전부 `| null`입니다. 프런트는 그 null을 그대로 "—"로 표시하고,
 * 절대 0으로 바꾸지 않습니다. 가져올 때는 `@/lib/types`(배럴)를 그대로 쓰면 됩니다.
 */
export interface TaskSummary {
  task: string;
  speed: string | null;
  status: "ok" | "empty" | "error";
  startedAt: string | null;
  durationMs: number | null;
  detail: string | null;
}

export interface StatusResponse {
  readMode: string;
  collectorReachable: boolean;
  message?: string;
  keys?: Record<string, boolean>;
  intervals?: Record<string, number>;
  missingDatasets?: { name: string; label: string }[];
  lastRun?: {
    id?: number;
    startedAt?: string;
    finishedAt?: string;
    status?: string;
    okCount?: number;
    failCount?: number;
    detail?: string;
    pid?: number;
    groupName?: string;
  } | null;
  lastRunStatus?: string;
  taskSummary?: TaskSummary[];
  timeseriesRows?: number;
  observationRows?: number;
  snapshots?: {
    name: string;
    status: string;
    error: string | null;
    collectedAt: string | null;
    ageSeconds?: number;
    stale?: boolean;
  }[];
  radarHistoryDates?: string[];
}

export interface VerificationResult {
  name: string;
  verdict: "match" | "mismatch" | "skipped" | "error";
  label: string;
  tolerancePct: number | null;
  diffPct: number | null;
  note: string | null;
  readings: { source: string; ok: boolean; value: number | null; detail: string | null }[];
}

export interface VerificationResponse {
  available: boolean;
  message?: string;
  checkedAt?: string;
  headline?: string;
  matchCount?: number;
  mismatchCount?: number;
  errorCount?: number;
  skippedCount?: number;
  keys?: { krx: boolean; kis: boolean; toss?: boolean };
  results?: VerificationResult[];
  exitCode?: number;
}

export interface DiagnosticsResponse {
  available: boolean;
  message?: string;
  checkedAt?: string;
  sources?: Record<
    string,
    { ok: boolean; stage: string; message: string; sample?: unknown }
  >;
}

/** GET /api/status/public-apis — 국내 공공 API 연결 진단 (키 값은 들어 있지 않음). */
export interface PublicApiDiagnosticsResponse {
  available: boolean;
  message?: string;
  checkedAt?: string;
  keys?: Record<string, boolean>;
  apis?: { label: string; configured: boolean; ok: boolean; detail: string | null; elapsedMs: number | null }[];
}

/** GET /api/status/issues — 수집 오류·경고 모음. text는 복사용(비밀값 가림). */
export interface StatusIssuesResponse {
  generatedAt: string;
  collectorReachable: boolean;
  lookbackHours: number;
  lastRunStatus?: string | null;
  counts: { errors: number; warnings: number; recentGroups: number; missingDatasets: number };
  current: { level: "error" | "warning"; task: string; status: string; at: string; detail: string }[];
  recent: { level: "error" | "warning"; task: string; count: number; firstAt: string; at: string; detail: string }[];
  missing: string[];
  text: string;
}
