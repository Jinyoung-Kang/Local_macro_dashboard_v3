/** 로그인한 사용자 — GET /api/auth/session의 user. 막는 것은 백엔드이고, 화면은 메뉴를 고르는 데만 씁니다. */
export interface SessionUser {
  /** 데모 열람이면 null(계정이 없음). */
  email: string | null;
  role: "OWNER" | "MEMBER";
  tier: "FREE" | "PRO";
  /**
   * 데모 열람(ADR 0013) — AWS 공개 데모에서 접근 코드를 통과한 방문자. 계정·세션 없이 읽기 전용이라
   * 로그아웃·내 계정·쓰기 버튼을 숨기거나 막습니다(백엔드도 GET 밖은 403).
   */
  demo?: boolean;
}

export interface SessionResponse {
  authenticated: boolean;
  /** 가입·비밀번호 재설정을 쓸 수 있는지(백엔드에 메일 발송이 설정됐는지). */
  mailAvailable?: boolean;
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

/** 사용자 관리 목록의 한 줄. */
export interface AdminUser {
  id: number;
  email: string;
  role: SessionUser["role"];
  tier: SessionUser["tier"];
  verified: boolean;
  createdAt: string;
}

export interface AuditEntry {
  at: string;
  userId: number | null;
  action: string;
  detail: string;
}
