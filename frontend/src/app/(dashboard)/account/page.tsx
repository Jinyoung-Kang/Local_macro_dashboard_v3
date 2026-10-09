"use client";

import { FormEvent, useState } from "react";
import { Banner, Button, Card, ErrorState, Loading, Table } from "@/components/ui";
import { useApi } from "@/hooks/useApi";
import { useAsyncAction } from "@/hooks/useAsyncAction";
import { roleLabel } from "@/lib/access";
import { auth } from "@/lib/api";
import { endpoints } from "@/lib/endpoints";
import { formatKst } from "@/lib/format";
import type { LoginSession, SessionResponse } from "@/lib/types";

/**
 * 👤 내 계정 — 계정 정보, 비밀번호 변경, 로그인한 기기(세션) 목록과 "다른 기기 모두 로그아웃".
 *
 * 비밀번호를 바꾸면 백엔드가 다른 기기의 세션을 끊습니다(지금 기기는 남김, ADR 0010).
 */
export default function AccountPage() {
  const session = useApi<SessionResponse>(endpoints.auth.session, 0, { followRefresh: false });
  const sessions = useApi<{ sessions: LoginSession[] }>(endpoints.auth.sessions, 0);
  const user = session.data?.user;

  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const change = useAsyncAction(auth.changePassword, "비밀번호를 바꾸지 못했습니다.");
  const others = useAsyncAction(auth.logoutOthers, "다른 기기 로그아웃에 실패했습니다.");

  const mismatch = confirm.length > 0 && next !== confirm;
  const [deletePassword, setDeletePassword] = useState("");
  const remove = useAsyncAction(auth.deleteAccount, "계정을 지우지 못했습니다.");

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (mismatch) return;
    await change.run(current, next);
    setCurrent("");
    setNext("");
    setConfirm("");
    void sessions.reload();
  };

  if (session.loading && !session.data) return <Loading label="계정 정보를 읽는 중…" />;
  if (session.error) return <ErrorState message={session.error} onRetry={session.reload} />;

  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="text-xl font-bold text-bright">내 계정</h1>
        <p className="mt-1 text-xs text-muted">
          {user?.email} · {roleLabel(user)}
        </p>
      </header>

      <Card title="🔑 비밀번호 변경" subtitle="10자 이상, 흔한 비밀번호·이메일을 담은 비밀번호는 쓸 수 없습니다. 바꾸면 다른 기기의 로그인은 끊깁니다.">
        <form onSubmit={submit} className="flex max-w-sm flex-col gap-3">
          {[
            { label: "지금 비밀번호", value: current, set: setCurrent, auto: "current-password" },
            { label: "새 비밀번호", value: next, set: setNext, auto: "new-password" },
            { label: "새 비밀번호 확인", value: confirm, set: setConfirm, auto: "new-password" },
          ].map((field) => (
            <label key={field.label} className="text-xs text-muted">
              {field.label}
              <input
                type="password"
                value={field.value}
                autoComplete={field.auto}
                onChange={(event) => field.set(event.target.value)}
                className="mt-1 w-full rounded-md border border-border bg-canvas px-3 py-2 text-sm text-body outline-none focus:border-accent"
              />
            </label>
          ))}
          {mismatch && <p className="text-xs text-danger">새 비밀번호가 서로 다릅니다.</p>}
          {change.error && <Banner tone="warn">{change.error}</Banner>}
          {change.result?.ok && <Banner tone="info">{change.result.message}</Banner>}
          <Button
            variant="primary"
            type="submit"
            disabled={change.busy || !current || !next || mismatch}
          >
            {change.busy ? "바꾸는 중…" : "비밀번호 바꾸기"}
          </Button>
        </form>
      </Card>

      {user?.role !== "OWNER" && (
        <Card title="🗑️ 계정 삭제" subtitle="계정과 개인정보(이메일·로그인 기기·알림 설정)를 바로 지웁니다. 되돌릴 수 없습니다.">
          {remove.result?.ok ? (
            <Banner tone="info">{remove.result.message} <a href="/login" className="underline">로그인 화면으로</a></Banner>
          ) : (
            <form
              onSubmit={(event) => {
                event.preventDefault();
                if (window.confirm("계정을 지울까요? 되돌릴 수 없습니다.")) void remove.run(deletePassword);
              }}
              className="flex max-w-sm flex-col gap-3"
            >
              <label className="text-xs text-muted">
                비밀번호 확인
                <input
                  type="password"
                  value={deletePassword}
                  autoComplete="current-password"
                  onChange={(event) => setDeletePassword(event.target.value)}
                  className="mt-1 w-full rounded-md border border-border bg-canvas px-3 py-2 text-sm text-body outline-none focus:border-accent"
                />
              </label>
              {remove.error && <Banner tone="warn">{remove.error}</Banner>}
              <Button type="submit" disabled={remove.busy || !deletePassword}>
                {remove.busy ? "지우는 중…" : "계정 지우기"}
              </Button>
            </form>
          )}
        </Card>
      )}

      <Card
        title="💻 로그인한 기기"
        subtitle="이 계정으로 로그인돼 있는 세션입니다. 모르는 기기가 있으면 다른 기기를 모두 로그아웃하고 비밀번호를 바꾸세요."
        actions={
          <Button
            onClick={async () => {
              await others.run();
              void sessions.reload();
            }}
            disabled={others.busy}
          >
            {others.busy ? "처리 중…" : "다른 기기 모두 로그아웃"}
          </Button>
        }
      >
        {others.result && <Banner tone="info">다른 기기 {others.result.revoked}곳의 로그인을 끊었습니다.</Banner>}
        {others.error && <Banner tone="warn">{others.error}</Banner>}
        {sessions.error && <ErrorState message={sessions.error} onRetry={sessions.reload} />}
        <Table<LoginSession>
          rows={sessions.data?.sessions ?? []}
          rowKey={(row) => row.id}
          emptyMessage="로그인한 기기가 없습니다."
          columns={[
            { key: "device", header: "기기", render: (row) => (row.current ? "🟢 지금 이 기기" : row.userAgent ?? "알 수 없음") },
            { key: "lastSeen", header: "마지막 사용", render: (row) => formatKst(row.lastSeenAt) },
            { key: "created", header: "로그인", render: (row) => formatKst(row.createdAt) },
            { key: "expires", header: "만료", render: (row) => formatKst(row.expiresAt) },
          ]}
        />
      </Card>
    </div>
  );
}
