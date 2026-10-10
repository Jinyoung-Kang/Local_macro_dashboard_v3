/**
 * 화면에서 메뉴를 고르는 규칙 — React를 모르는 순수 함수(ADR 0004).
 *
 * 막는 것은 백엔드의 경로별 접근 정책(ADR 0010)입니다. 여기서는 일반 회원이 눌러 봐야 403만 받는 메뉴·버튼을
 * 처음부터 보이지 않게 할 뿐입니다. 백엔드 정책과 어긋나면 "버튼은 보이는데 403"이 되므로, 운영 메뉴는
 * 백엔드에서 운영자 전용인 경로만 부르는 화면만 적습니다.
 */
import type { SessionUser } from "./types";

/** 운영자만 보는 메뉴 — 수집 실행·내부 상태(/api/status*)와 키 설정 진단·유료 AI 시험 호출을 부르는 화면. */
export const OWNER_ONLY_MENUS = ["/status", "/connections", "/admin/users"] as const;

/** 유료 등급 메뉴 — 무료 회원에게는 🔒을 붙입니다(눌러도 되며, 화면이 유료 안내를 띄움). */
export const PRO_MENUS = ["/ai/report"] as const;

/** 무료 회원에게 잠긴 메뉴인가. */
export function lockedFor(user: SessionUser | null | undefined, href: string): boolean {
  if (!user || user.role === "OWNER" || user.tier === "PRO") return false;
  return (PRO_MENUS as readonly string[]).includes(href);
}

export function isOwner(user: SessionUser | null | undefined): boolean {
  return user?.role === "OWNER";
}

/** 이 사용자에게 보여 줄 메뉴. 사용자를 모르면(세션 응답 전) 운영 메뉴는 숨깁니다. */
export function visibleMenus<T extends { href: string }>(menus: readonly T[], user: SessionUser | null | undefined): T[] {
  if (isOwner(user)) return [...menus];
  return menus.filter((menu) => !(OWNER_ONLY_MENUS as readonly string[]).includes(menu.href));
}

/** 데모 열람인가(ADR 0013) — 계정 없이 읽기만 하는 방문자. */
export function isDemo(user: SessionUser | null | undefined): boolean {
  return user?.demo === true;
}

/** 데모 열람에서 쓰기 버튼 옆에 적는 안내. 백엔드의 403 문구와 같은 뜻입니다. */
export const DEMO_READ_ONLY = "데모에서는 조회만 가능합니다";

/** 사이드바에 적을 역할·등급 이름. */
export function roleLabel(user: SessionUser | null | undefined): string {
  if (!user) return "";
  if (isDemo(user)) return "데모 열람";
  if (user.role === "OWNER") return "운영자";
  return user.tier === "PRO" ? "유료 회원" : "무료 회원";
}
