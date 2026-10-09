package com.macrodash.feature.auth;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 해시 — Argon2id(OWASP 비밀번호 저장 권장 1순위).
 *
 * <p>설정은 Spring Security의 권장 기본값(메모리 16MB·반복 2·병렬 1·솔트 16바이트·해시 32바이트)입니다.
 * 로그인 한 번에 수십 ms가 걸리는 것이 목적입니다 — 유출된 해시를 대입해 보는 비용을 올립니다.
 */
@Component
public class PasswordHasher {

    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    /** 없는 계정으로 로그인할 때도 같은 시간이 걸리게 비교할 가짜 해시(계정 존재 여부를 응답 시간으로 드러내지 않음). */
    private final String dummyHash = encoder.encode("dummy-password-for-constant-time");

    public String hash(String password) {
        return encoder.encode(password);
    }

    public boolean matches(String password, String hash) {
        if (password == null || hash == null) {
            return false;
        }
        return encoder.matches(password, hash);
    }

    /** 결과는 늘 false. 계정이 없을 때 시간을 맞추려고 부릅니다. */
    public boolean matchesDummy(String password) {
        encoder.matches(password == null ? "" : password, dummyHash);
        return false;
    }
}
