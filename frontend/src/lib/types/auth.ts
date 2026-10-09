/** 로그인한 사용자 — GET /api/auth/session의 user. 막는 것은 백엔드이고, 화면은 메뉴를 고르는 데만 씁니다. */
export interface SessionUser {
  email: string;
  role: "OWNER" | "MEMBER";
  tier: "FREE" | "PRO";
}

export interface SessionResponse {
  authenticated: boolean;
  readMode?: string;
  user?: SessionUser;
}

export interface LoginSession {
  id: string;
  current: boolean;
  createdAt: string;
  lastSeenAt: string;
  expiresAt: string;
  userAgent: string | null;
}
