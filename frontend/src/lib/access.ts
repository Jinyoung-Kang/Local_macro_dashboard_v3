/**
 * 화면에서 메뉴를 고르는 규칙 — React를 모르는 순수 함수(ADR 0004).
 *
 * 막는 것은 백엔드의 경로별 접근 정책(ADR 0010)입니다. 여기서는 일반 회원이 눌러 봐야 403만 받는 메뉴·버튼을
 * 처음부터 보이지 않게 할 뿐입니다. 백엔드 정책과 어긋나면 "버튼은 보이는데 403"이 되므로, 운영 메뉴는
 * 백엔드에서 운영자 전용인 경로만 부르는 화면만 적습니다.
 */
import type { SessionUser } from "./types";

/** 운영자만 보는 메뉴 — 수집 실행·내부 상태(/api/status*)와 키 설정 진단·유료 AI 시험 호출을 부르는 화면. */
export const OWNER_ONLY_MENUS = ["/status", "/connections"] as const;

export function isOwner(user: SessionUser | null | undefined): boolean {
  return user?.role === "OWNER";
}

/** 이 사용자에게 보여 줄 메뉴. 사용자를 모르면(세션 응답 전) 운영 메뉴는 숨깁니다. */
export function visibleMenus<T extends { href: string }>(menus: readonly T[], user: SessionUser | null | undefined): T[] {
  if (isOwner(user)) return [...menus];
  return menus.filter((menu) => !(OWNER_ONLY_MENUS as readonly string[]).includes(menu.href));
}

/** 사이드바에 적을 역할·등급 이름. */
export function roleLabel(user: SessionUser | null | undefined): string {
  if (!user) return "";
  if (user.role === "OWNER") return "운영자";
  return user.tier === "PRO" ? "유료 회원" : "무료 회원";
}
