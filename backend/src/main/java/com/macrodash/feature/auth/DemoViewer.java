package com.macrodash.feature.auth;

import com.macrodash.support.CurrentUser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 데모 열람(ADR 0013) — AWS 공개 데모에서 접근 코드를 통과한 방문자를 로그인 없이 읽기 전용으로 엽니다.
 *
 * <p>앞단 프록시(Caddy)의 공개 쪽이 접근 코드를 확인한 요청에만 {@link #HEADER}로 토큰을 실어 보내고, 운영자용
 * 쪽은 이 헤더를 지웁니다. 백엔드는 127.0.0.1에만 열려 있어 프록시를 거치지 않고는 닿지 않습니다.
 *
 * <ul>
 *   <li>토큰이 비면 꺼집니다(기본·로컬) — 헤더가 와도 보지 않습니다.</li>
 *   <li>토큰이 있으면 32자 이상이어야 합니다. 짧으면 기동을 멈춥니다(약한 토큰으로 조용히 켜지지 않게).</li>
 *   <li>비교는 양쪽의 SHA-256을 {@link MessageDigest#isEqual}로 — 걸린 시간으로 토큰의 앞부분·길이를 알아낼 수 없습니다.</li>
 *   <li>세션 쿠키가 유효하면 세션이 이깁니다(이 판정은 {@link AuthService#resolve}).</li>
 * </ul>
 */
public final class DemoViewer {

    /** 앞단 프록시가 붙이는 요청 헤더. */
    public static final String HEADER = "X-Demo-Viewer";

    /** 데모 열람 사용자 번호 — users.id(BIGSERIAL, 1부터)와 겹치지 않습니다. 이 번호로 쓰면 사용자 FK가 거절합니다. */
    public static final long USER_ID = -1L;

    static final int MIN_TOKEN_LENGTH = 32;

    /** 역할은 일반 회원(운영 경로 403), 등급은 유료(유료 패널까지 보임). 이메일·세션은 없습니다. */
    private static final CurrentUser USER = new CurrentUser(USER_ID, null, "MEMBER", "PRO", null, true);

    /** 설정한 토큰의 SHA-256. 꺼져 있으면 null. */
    private final byte[] expected;

    private DemoViewer(byte[] expected) {
        this.expected = expected;
    }

    /**
     * 설정값으로 만듭니다.
     *
     * @throws IllegalStateException 비어 있지 않은데 32자보다 짧을 때(값은 메시지에 담지 않음)
     */
    public static DemoViewer from(String configured) {
        if (configured == null || configured.isBlank()) {
            return new DemoViewer(null);
        }
        String token = configured.strip();
        if (token.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalStateException(("DASHBOARD_DEMO_VIEWER_TOKEN이 %d자로 너무 짧습니다 — 데모 열람을 켜려면 %d자 이상"
                    + "(예: openssl rand -hex 32)으로, 끄려면 비워 두세요. 약한 토큰으로 켜지 않도록 기동을 멈춥니다(ADR 0013).")
                    .formatted(token.length(), MIN_TOKEN_LENGTH));
        }
        return new DemoViewer(sha256(token));
    }

    public boolean enabled() {
        return expected != null;
    }

    /** 이 헤더 값이 데모 열람 토큰인가. 꺼져 있거나 헤더가 없거나 비었으면 false. */
    public boolean matches(String presented) {
        if (expected == null || presented == null || presented.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(expected, sha256(presented));
    }

    /** 데모 열람 사용자 — 계정·세션 없이 요청 동안만 쓰는 값입니다. */
    public static CurrentUser user() {
        return USER;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }
}
