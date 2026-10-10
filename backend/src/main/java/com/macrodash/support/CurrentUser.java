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
 * @param demo 데모 열람(ADR 0013) — 계정·세션이 없는 읽기 전용 방문자. {@code id}는 실제 계정과 겹치지 않는 음수,
 *             {@code email}·{@code sessionId}는 null입니다. 이 사용자로 DB에 쓰면 안 됩니다(사용자 FK가 막음).
 */
public record CurrentUser(long id, String email, String role, String tier, UUID sessionId, boolean demo) {

    public static final String ATTRIBUTE = CurrentUser.class.getName();

    /** 계정으로 로그인한 사용자(데모 열람이 아님). */
    public CurrentUser(long id, String email, String role, String tier, UUID sessionId) {
        this(id, email, role, tier, sessionId, false);
    }

    public boolean isOwner() {
        return "OWNER".equals(role);
    }

    /** 유료 기능을 쓸 수 있는가. 운영자는 등급과 무관하게 모두 씁니다. */
    public boolean isPro() {
        return isOwner() || "PRO".equals(tier);
    }

    /**
     * 이 요청이 유료 범위를 볼 수 있는가(운영자 포함). 사용자가 없으면 볼 수 없습니다 — 예전에는 테스트 편의로 '제한 없음'으로
     * 봐서, 필터 설정이 어긋나 사용자 없이 들어온 요청에 유료 범위가 열릴 수 있었습니다(QA-043).
     */
    public static boolean paid(HttpServletRequest request) {
        CurrentUser user = of(request);
        return user != null && user.isPro();
    }

    /** 세션 필터를 통과한 요청이면 항상 있습니다. 공개 경로에서는 null일 수 있습니다. */
    public static CurrentUser of(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof CurrentUser user ? user : null;
    }
}
