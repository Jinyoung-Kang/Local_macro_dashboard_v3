package com.macrodash.support;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

/**
 * 지금 요청을 보낸 사용자 — 세션 필터가 요청 속성에 넣습니다(ADR 0010).
 *
 * <p>{@code support}에 두는 이유: 컨트롤러는 자기 기능의 서비스와 {@code support}·{@code config}만 쓸 수 있습니다
 * (ADR 0001 규칙 2). 다른 기능의 컨트롤러도 사용자 정보를 서비스에 넘길 수 있어야 해서 공통 자리에 둡니다.
 *
 * @param role {@code OWNER} 또는 {@code MEMBER}
 * @param tier {@code FREE} 또는 {@code PRO}
 */
public record CurrentUser(long id, String email, String role, String tier, UUID sessionId) {

    public static final String ATTRIBUTE = CurrentUser.class.getName();

    public boolean isOwner() {
        return "OWNER".equals(role);
    }

    /** 유료 기능을 쓸 수 있는가. 운영자는 등급과 무관하게 모두 씁니다. */
    public boolean isPro() {
        return isOwner() || "PRO".equals(tier);
    }

    /** 세션 필터를 통과한 요청이면 항상 있습니다. 공개 경로에서는 null일 수 있습니다. */
    public static CurrentUser of(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof CurrentUser user ? user : null;
    }
}
