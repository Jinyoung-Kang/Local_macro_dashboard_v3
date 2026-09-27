"use client";

import { useState } from "react";
import { Banner, Button, Card, Loading, SourceBadge } from "@/components/ui";
import { useApi } from "@/hooks/useApi";
import { apiGet } from "@/lib/api";
import { SOURCES } from "@/lib/sources";

/**
 * 🔌 토스증권 — 연결 진단·조회 테스트.
 *
 * 이 프로젝트에서 토스는 <b>연결 진단 전용</b>입니다. 대시보드 수치는 토스에서
 * 가져오지 않습니다. 키가 없으면 이 영역만 비활성화됩니다.
 *
 * 주의사항 — 진단은 토스 서버를 실제로 부릅니다. 메뉴를 열 때마다 자동으로 부르면
 * 보지도 않는 호출이 쌓이므로 버튼을 눌렀을 때만 실행합니다.
 */
export function TossSection() {
  const [runId, setRunId] = useState<number | null>(null);
  const diagnostics = useApi<{ ok: boolean; stage?: string; message?: string; sample?: unknown }>(
    runId === null ? null : `/api/ai/toss/diagnostics?run=${runId}`,
  );
  const [result, setResult] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const call = async (path: string) => {
    setBusy(true);
    setError(null);
    try {
      setResult(await apiGet(path));
    } catch (err) {
      setError(err instanceof Error ? err.message : "호출에 실패했습니다.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="flex flex-col gap-4">
      <h2 className="text-base font-semibold text-bright">🔌 토스증권</h2>

      <Card
        title="연결 상태" source={SOURCES.toss}
        subtitle="연결 진단·조회 테스트입니다. 📡 수급 레이더(시장 전체·수급 지속성·폴백 순위)와 🇰🇷 국내 파생(현물·선물 수급 동조)도 토스 공식 데이터를 씁니다"
        actions={
          <Button onClick={() => setRunId(Date.now())} disabled={diagnostics.loading}>
            {diagnostics.loading ? "검사 중…" : "진단 실행"}
          </Button>
        }
      >
        {runId === null && <p className="text-sm text-muted">버튼을 누르면 토스 서버에 한 번 연결해 봅니다.</p>}
        {diagnostics.loading && !diagnostics.data && <Loading />}
        {diagnostics.data && (
          <div className="flex flex-col gap-2">
            <p className={diagnostics.data.ok ? "text-ok" : "text-warn"}>
              {diagnostics.data.ok ? "✅ 연결 성공" : "⚠️ 연결 실패"}{" "}
              {diagnostics.data.stage && <SourceBadge>{diagnostics.data.stage}</SourceBadge>}
            </p>
            <p className="text-sm text-muted">{diagnostics.data.message}</p>
          </div>
        )}
      </Card>

      <Card title="실제 데이터 조회 테스트" source={SOURCES.toss}>
        <div className="flex flex-wrap gap-2">
          <Button
            onClick={() => call("/api/ai/toss/exchange-rate?base=USD&quote=KRW")}
            disabled={busy}
          >
            환율 조회 (USD → KRW)
          </Button>
          <Button
            onClick={() => call("/api/ai/toss/indices?symbols=KOSPI,KOSDAQ")}
            disabled={busy}
          >
            지수 시세 조회
          </Button>
        </div>

        {error && (
          <div className="mt-4">
            <Banner tone="danger">{error}</Banner>
          </div>
        )}

        {result !== null && (
          <pre className="mt-4 max-h-80 overflow-auto rounded border border-border bg-canvas p-3 text-[11px] text-muted">
            {JSON.stringify(result, null, 2)}
          </pre>
        )}
      </Card>

      <Card title="참고">
        <ul className="list-disc pl-5 text-xs leading-relaxed text-muted">
          <li>토스 Open API는 허용 IP 목록을 사용합니다. HTTP 403이면 IP 등록을 확인하세요.</li>
          <li>키는 수집기 쪽에만 설정합니다(백엔드·프런트는 키를 보관하지 않습니다).</li>
          <li>키가 없으면 이 영역만 비활성화되고 다른 메뉴는 정상 동작합니다.</li>
        </ul>
      </Card>
    </section>
  );
}
