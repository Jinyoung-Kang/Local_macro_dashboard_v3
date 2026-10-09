"use client";

import { FormEvent, useState } from "react";
import { Banner, Button, Card, ErrorState, Loading, Select, Table } from "@/components/ui";
import { useApi } from "@/hooks/useApi";
import { useAsyncAction } from "@/hooks/useAsyncAction";
import { apiPost } from "@/lib/api";
import { endpoints } from "@/lib/endpoints";
import { formatKst } from "@/lib/format";
import type { AdminUser, AuditEntry } from "@/lib/types";

type Result = { ok: boolean; message: string };

/**
 * 👥 사용자 관리(운영자만 — 백엔드 접근 정책이 막음). 결제 연동 전까지 등급은 여기서 정합니다(명세 D4).
 * 바꾼 역할·등급은 그 사용자의 다음 요청부터 반영됩니다.
 */
export default function AdminUsersPage() {
  const users = useApi<{ users: AdminUser[] }>(endpoints.admin.users, 0);
  const audit = useApi<{ entries: AuditEntry[] }>(endpoints.admin.audit(100), 0);

  const update = useAsyncAction(
    (id: number, role: string, tier: string) => apiPost<Result>(endpoints.admin.user(id), { role, tier }),
    "바꾸지 못했습니다.",
  );
  const create = useAsyncAction(
    (email: string, password: string, tier: string) => apiPost<Result>(endpoints.admin.users, { email, password, tier }),
    "회원을 만들지 못했습니다.",
  );

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [tier, setTier] = useState("FREE");

  const change = async (row: AdminUser, role: string, nextTier: string) => {
    await update.run(row.id, role, nextTier);
    void users.reload();
    void audit.reload();
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    await create.run(email, password, tier);
    setPassword("");
    void users.reload();
    void audit.reload();
  };

  if (users.loading && !users.data) return <Loading label="사용자 목록을 읽는 중…" />;
  if (users.error) return <ErrorState message={users.error} onRetry={users.reload} />;

  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="text-xl font-bold text-bright">사용자 관리</h1>
        <p className="mt-1 text-xs text-muted">역할(운영자·회원)과 등급(무료·유료)을 정합니다. 자기 운영자 역할은 내릴 수 없습니다.</p>
      </header>

      <Card title="사용자" subtitle={`${users.data?.users.length ?? 0}명`}>
        {update.error && <Banner tone="warn">{update.error}</Banner>}
        {update.result && <Banner tone={update.result.ok ? "info" : "warn"}>{update.result.message}</Banner>}
        <Table<AdminUser>
          rows={users.data?.users ?? []}
          rowKey={(row) => String(row.id)}
          columns={[
            { key: "email", header: "이메일", render: (row) => row.email },
            {
              key: "role",
              header: "역할",
              render: (row) => (
                <Select
                  label={`${row.email} 역할`}
                  value={row.role}
                  onChange={(value) => void change(row, value, row.tier)}
                  options={[{ value: "OWNER", label: "운영자" }, { value: "MEMBER", label: "회원" }]}
                />
              ),
            },
            {
              key: "tier",
              header: "등급",
              render: (row) => (
                <Select
                  label={`${row.email} 등급`}
                  value={row.tier}
                  onChange={(value) => void change(row, row.role, value)}
                  options={[{ value: "FREE", label: "무료" }, { value: "PRO", label: "유료" }]}
                />
              ),
            },
            { key: "verified", header: "메일 인증", render: (row) => (row.verified ? "✅" : "대기") },
            { key: "created", header: "가입", render: (row) => formatKst(row.createdAt) },
          ]}
        />
      </Card>

      <Card title="➕ 회원 만들기" subtitle="메일 없이 인증된 회원을 만듭니다. 처음 비밀번호는 직접 전달하고, 회원이 👤 내 계정에서 바꾸도록 안내하세요.">
        <form onSubmit={submit} className="flex max-w-sm flex-col gap-3">
          <label className="text-xs text-muted">
            이메일
            <input type="email" value={email} onChange={(event) => setEmail(event.target.value)}
              className="mt-1 w-full rounded-md border border-border bg-canvas px-3 py-2 text-sm text-body outline-none focus:border-accent" />
          </label>
          <label className="text-xs text-muted">
            처음 비밀번호(10자 이상)
            <input type="password" autoComplete="new-password" value={password} onChange={(event) => setPassword(event.target.value)}
              className="mt-1 w-full rounded-md border border-border bg-canvas px-3 py-2 text-sm text-body outline-none focus:border-accent" />
          </label>
          <Select label="등급" value={tier} onChange={setTier}
            options={[{ value: "FREE", label: "무료" }, { value: "PRO", label: "유료" }]} />
          {create.error && <Banner tone="warn">{create.error}</Banner>}
          {create.result && <Banner tone={create.result.ok ? "info" : "warn"}>{create.result.message}</Banner>}
          <Button variant="primary" type="submit" disabled={create.busy || !email || !password}>
            {create.busy ? "만드는 중…" : "회원 만들기"}
          </Button>
        </form>
      </Card>

      <Card title="🧾 감사 기록" subtitle="로그인·비밀번호·역할/등급 변경·계정 삭제(최근 100건). 비밀번호·토큰 값은 남기지 않습니다.">
        {audit.error && <ErrorState message={audit.error} onRetry={audit.reload} />}
        <Table<AuditEntry>
          rows={audit.data?.entries ?? []}
          rowKey={(row, index) => `${row.at}-${index}`}
          columns={[
            { key: "at", header: "시각", render: (row) => formatKst(row.at) },
            { key: "user", header: "사용자 번호", render: (row) => (row.userId === null ? "—" : String(row.userId)) },
            { key: "action", header: "동작", render: (row) => row.action },
            { key: "detail", header: "내용", render: (row) => <span className="text-xs text-muted">{row.detail}</span> },
          ]}
        />
      </Card>
    </div>
  );
}
