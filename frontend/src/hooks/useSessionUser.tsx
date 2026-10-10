"use client";

import { createContext, useContext } from "react";
import type { SessionUser } from "@/lib/types";

/**
 * 지금 화면을 보는 사용자 — 대시보드 레이아웃이 세션 확인(GET /api/auth/session) 결과를 내려 줍니다.
 *
 * 화면마다 세션을 다시 읽지 않으려고 둡니다. 막는 것은 백엔드이고, 화면은 버튼을 숨기거나 막는 데만 씁니다
 * (데모 열람의 쓰기 버튼 등, ADR 0013). 레이아웃 밖(화면만 그리는 테스트)에서는 undefined입니다.
 */
const SessionUserContext = createContext<SessionUser | undefined>(undefined);

export function SessionUserProvider({
  user,
  children,
}: {
  user: SessionUser | undefined;
  children: React.ReactNode;
}) {
  return <SessionUserContext.Provider value={user}>{children}</SessionUserContext.Provider>;
}

export function useSessionUser(): SessionUser | undefined {
  return useContext(SessionUserContext);
}
