"use client";

import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { MarketClock } from "@/components/MarketClock";
import { MENUS, Sidebar } from "@/components/Sidebar";
import { useApi } from "@/hooks/useApi";
import { apiBase } from "@/lib/api";
import { RefreshProvider, useRefreshSignal } from "@/hooks/useRefreshSignal";
import { endpoints } from "@/lib/endpoints";

/**
 * 대시보드 공통 레이아웃 — 사이드바 + 거래소 시계.
 *
 * 세션이 없으면 로그인 화면으로 보냅니다. 구버전의 "비밀번호 잠금"과 같은
 * 역할이며, 실제 접근 차단은 백엔드가 합니다(프런트 검사는 UX용입니다).
 */
export default function DashboardLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  // 레이아웃 자신도 useApi를 쓰므로 Provider 안쪽에 있어야 합니다.
  // (수동 새로고침이 사이드바의 읽기 모드 표시까지 갱신합니다.)
  return (
    <RefreshProvider>
      <DashboardShell>{children}</DashboardShell>
    </RefreshProvider>
  );
}

/**
 * 브라우저 탭 제목을 현재 메뉴 이름으로 바꿉니다.
 *
 * <p>모든 화면이 클라이언트 컴포넌트라 Next의 정적 metadata를 페이지마다 둘 수
 * 없습니다. 그대로 두면 탭 12개가 전부 같은 제목이라, 매크로·레이더·13F를 함께
 * 띄워 두고 비교할 때 어느 탭이 무엇인지 구분할 수 없습니다.
 */
function useDocumentTitle(pathname: string) {
  useEffect(() => {
    const menu = MENUS.find(
      (entry) => pathname === entry.href || pathname.startsWith(`${entry.href}/`),
    );
    // 이모지는 탭에서 잘리기 쉬워 떼고, 뒤에 앱 이름을 붙입니다.
    // 🏛️처럼 이모지 뒤에 이형 선택자(U+FE0F)가 붙는 글자가 있어, 그림 문자만
    // 지우면 보이지 않는 문자가 제목 앞에 남습니다. 함께 지웁니다.
    const name = menu?.label
      .replace(/^[\p{Extended_Pictographic}\u200d\ufe0f\s]+/u, "")
      .trim();
    document.title = name ? `${name} · 매크로 대시보드` : "매크로 대시보드";
  }, [pathname]);
}

/** 세션 확인이 이 시간 안에 끝나지 않으면 "응답 없음"으로 보여 줍니다. */
const SESSION_TIMEOUT_MS = 10_000;
/** 백엔드에 닿지 못했을 때 자동으로 다시 확인하는 간격. 백엔드가 뜨면 스스로 풀립니다. */
const SESSION_RETRY_MS = 5_000;

function DashboardShell({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  const router = useRouter();
  const pathname = usePathname();
  useDocumentTitle(pathname);
  // 수동 새로고침에는 반응하지 않습니다(followRefresh: false). 반응하면 새로고침마다
  // 아래의 "세션을 확인하는 중…"이 떠 화면 전체가 다시 그려지고 화면 상태가 초기화됐습니다.
  const session = useApi<{ authenticated: boolean; readMode?: string }>(endpoints.auth.session, 0, {
    timeoutMs: SESSION_TIMEOUT_MS,
    followRefresh: false,
  });
  const { data, loading, unauthorized, error, reload } = session;
  // 세션은 처음 한 번만 확인합니다. 그 뒤 만료(12시간, 다른 탭 로그아웃)는 화면의 어떤 요청이든
  // 401을 받았을 때 올라오는 이 신호로 압니다.
  const { sessionLost } = useRefreshSignal();

  const authenticated = data?.authenticated ?? false;

  useEffect(() => {
    if (sessionLost || (!loading && (unauthorized || (data && !authenticated)))) {
      router.replace("/login");
    }
  }, [sessionLost, loading, unauthorized, data, authenticated, router]);

  // 백엔드에 닿지 못한 이유를 기억해 둡니다. 다시 확인하는 동안(loading) 안내 화면이
  // "세션을 확인하는 중…"으로 깜빡이지 않게, 성공하거나 401을 받을 때만 지웁니다.
  const [failure, setFailure] = useState<string | null>(null);
  useEffect(() => {
    if (error) setFailure(error);
    else if (data || unauthorized) setFailure(null);
  }, [error, data, unauthorized]);

  useEffect(() => {
    if (!error) return;
    const timer = setTimeout(() => void reload(), SESSION_RETRY_MS);
    return () => clearTimeout(timer);
  }, [error, reload]);

  // 예전에는 여기가 `loading || !authenticated`뿐이었습니다. 백엔드가 꺼져 있으면
  // 요청이 실패해 loading=false·data=null이 되는데, 로그인으로 보내는 조건(401 또는
  // authenticated=false 응답)에도 걸리지 않아 "세션을 확인하는 중…"에 영원히 멈췄습니다.
  if (failure && !authenticated) {
    return <BackendUnavailable reason={failure} retrying={loading} onRetry={() => void reload()} />;
  }

  // 이미 로그인이 확인됐으면, 다시 확인하는 동안(loading)에도 화면을 그대로 둡니다.
  if (!authenticated) {
    return (
      <main className="flex min-h-screen items-center justify-center text-sm text-muted">
        세션을 확인하는 중…
      </main>
    );
  }

  return (
    <div className="flex min-h-screen flex-col lg:flex-row">
      {/* 읽기 모드는 세션 응답에 함께 옵니다. 예전에는 이 한 줄 때문에 페이지를 열 때마다
          무거운 /api/status(수집기 상태 + DB 집계)를 불렀습니다. */}
      <Sidebar readMode={data?.readMode} />
      <main tabIndex={-1} id="main" className="flex-1 overflow-x-hidden p-4 sm:p-6 lg:h-screen lg:overflow-y-auto">
        <div className="mb-5">
          <MarketClock />
        </div>
        {children}
      </main>
    </div>
  );
}

/**
 * 백엔드 API에 닿지 못했을 때의 안내.
 *
 * <p>화면(3000)은 떠 있는데 API(8080)가 없는 상태입니다. 흔한 원인은 백엔드 컨테이너가
 * 기동에 실패한 것(예: 포트 충돌)이라, 사용자가 바로 확인할 명령을 적어 둡니다.
 * 5초마다 스스로 다시 확인하므로 백엔드가 뜨면 새로고침 없이 넘어갑니다.
 */
function BackendUnavailable({
  reason,
  retrying,
  onRetry,
}: {
  reason: string;
  retrying: boolean;
  onRetry: () => void;
}) {
  return (
    <main className="flex min-h-screen items-center justify-center px-4">
      <div className="w-full max-w-lg rounded-xl border border-warn/40 bg-surface p-6 text-sm">
        <h1 className="text-base font-bold text-bright">⚠️ 백엔드 API에 연결하지 못했습니다</h1>
        <p className="mt-2 text-warn">{reason}</p>
        <p className="mt-4 text-muted">
          화면은 떠 있지만 데이터를 주는 백엔드가 응답하지 않습니다. 터미널에서 확인하세요.
        </p>
        <pre className="mt-2 overflow-x-auto rounded-md border border-border bg-canvas px-3 py-2 text-xs text-body">
{`make doctor          # 어디가 막혔는지 한 번에
docker compose ps    # backend가 running인지
make logs S=backend  # 기동 실패 원인`}
        </pre>
        <p className="mt-3 text-xs text-muted">
          화면이 찾는 주소: <code className="text-body">{apiBase()}</code> ·{" "}
          {retrying ? "다시 확인하는 중…" : `${SESSION_RETRY_MS / 1000}초마다 자동으로 다시 확인합니다`}
        </p>
        <button
          type="button"
          onClick={onRetry}
          disabled={retrying}
          className="mt-4 rounded-md border border-accent/60 bg-accent/15 px-3 py-1.5 text-xs font-semibold text-accent transition hover:bg-accent/25 disabled:opacity-50"
        >
          지금 다시 확인
        </button>
      </div>
    </main>
  );
}
