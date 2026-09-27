package com.macrodash.service;

import com.macrodash.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthServiceTest {

    private AuthService authService;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.setPassword("s3cret-password");
        properties.setJwtSecret("test-secret-key-that-is-long-enough-32b");
        properties.setSessionMinutes(60);
        authService = new AuthService(properties);
    }

    @Test
    @DisplayName("올바른 비밀번호만 통과한다")
    void passwordCheck() {
        assertThat(authService.passwordMatches("s3cret-password")).isTrue();
        assertThat(authService.passwordMatches("wrong")).isFalse();
        assertThat(authService.passwordMatches(null)).isFalse();
        assertThat(authService.passwordMatches("")).isFalse();
    }

    @Test
    @DisplayName("발급한 토큰은 유효하다")
    void issuedTokenIsValid() {
        String token = authService.issueToken();

        assertThat(token).isNotBlank();
        assertThat(authService.isValid(token)).isTrue();
    }

    @Test
    @DisplayName("위조·빈 토큰은 거부된다")
    void invalidTokensRejected() {
        assertThat(authService.isValid(null)).isFalse();
        assertThat(authService.isValid("")).isFalse();
        assertThat(authService.isValid("not-a-token")).isFalse();
        assertThat(authService.isValid(authService.issueToken() + "x")).isFalse();
    }

    @Test
    @DisplayName("다른 서명 키로 만든 토큰은 거부된다")
    void tokenFromOtherKeyRejected() {
        AppProperties other = new AppProperties();
        other.setPassword("s3cret-password");
        other.setJwtSecret("completely-different-secret-key-32bytes");
        String foreignToken = new AuthService(other).issueToken();

        assertThat(authService.isValid(foreignToken)).isFalse();
    }

    @Test
    @DisplayName("짧은 서명 키도 기동은 되지만 토큰은 정상 동작한다")
    void shortSecretStillWorks() {
        AppProperties properties = new AppProperties();
        properties.setPassword("x");
        properties.setJwtSecret("short");
        AuthService service = new AuthService(properties);

        assertThat(service.isValid(service.issueToken())).isTrue();
    }

    @Test
    @DisplayName("공개된 기본 키·짧은 키로는 토큰을 위조할 수 없다 (실행마다 무작위 키)")
    void placeholderSecretIsNotForgeable() {
        for (String weak : new String[]{"change-me-please-change-me-please-32b", "short", ""}) {
            AppProperties a = new AppProperties();
            a.setJwtSecret(weak);
            AppProperties b = new AppProperties();
            b.setJwtSecret(weak);

            // 같은 약한 값을 아는 공격자(b)가 만든 토큰을 서버(a)가 받아 주면 안 됩니다.
            String forged = new AuthService(b).issueToken();
            assertThat(new AuthService(a).isValid(forged)).as(weak).isFalse();
        }
    }

    @Test
    @DisplayName("비밀번호를 바꾸면 이전 비밀번호로 받은 세션은 무효가 된다 (SEC-07)")
    void passwordChangeInvalidatesOldSessions() {
        // 비밀번호는 .env에서 바꾸고 재시작합니다. 서명 키(JWT_SECRET)는 그대로라
        // 예전에는 바꾸기 전에 받은 토큰이 만료(12시간)까지 계속 통했습니다.
        String before = authService.issueToken();

        AppProperties changed = new AppProperties();
        changed.setPassword("new-password-after-change");
        changed.setJwtSecret("test-secret-key-that-is-long-enough-32b");
        changed.setSessionMinutes(60);
        AuthService afterChange = new AuthService(changed);

        assertThat(afterChange.isValid(before)).isFalse();
        assertThat(afterChange.isValid(afterChange.issueToken())).isTrue();
    }

    @Test
    @DisplayName("로그아웃한 토큰만 거부되고 다른 세션은 그대로다 (SEC-07)")
    void revokedTokenIsRejected() {
        String loggedOut = authService.issueToken();
        String other = authService.issueToken();

        authService.revoke(loggedOut);
        authService.revoke(null);            // 쿠키 없이 로그아웃해도 문제없어야 합니다
        authService.revoke("not-a-token");

        assertThat(authService.isValid(loggedOut)).isFalse();
        assertThat(authService.isValid(other)).isTrue();
    }

    @Test
    @DisplayName("토큰에는 비밀번호도, 서명 키 없이 맞춰 볼 수 있는 단순 해시도 들어가지 않는다")
    void tokenDoesNotCarryThePassword() {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(authService.issueToken().split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(payload).doesNotContain("s3cret-password");
        byte[] other = "another-secret-key-that-is-long-enough".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // 같은 비밀번호라도 서명 키가 다르면 지문이 다릅니다(키 없이 사전 대입 불가).
        assertThat(AuthService.passwordStamp(other, "s3cret-password"))
                .isNotEqualTo(AuthService.passwordStamp(
                        "test-secret-key-that-is-long-enough-32b".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "s3cret-password"));
    }

    @Test
    @DisplayName("공개된 기본 비밀번호·빈 비밀번호로는 로그인할 수 없다 (SEC-04)")
    void publicDefaultPasswordIsNeverAccepted() {
        // admin1234@는 저장소·문서에 적혀 있던 값입니다. 화면은 기본으로 같은 와이파이에 열리므로
        // 이 값이 통하면 누구나 들어올 수 있었습니다(예전에는 경고 로그만 남겼습니다).
        for (String configured : new String[]{"admin1234@", "", "   ", null}) {
            AppProperties p = new AppProperties();
            p.setPassword(configured);
            p.setJwtSecret("test-secret-key-that-is-long-enough-32b");
            AuthService service = new AuthService(p);

            assertThat(service.passwordMatches("admin1234@")).as("설정값 %s", configured).isFalse();
            assertThat(service.passwordMatches("")).as("설정값 %s", configured).isFalse();
            // 대신 이번 실행 동안만 쓰는 임시 비밀번호로는 들어갈 수 있습니다(로그에 한 번 표시).
            String temporary = service.effectivePassword();
            assertThat(temporary).hasSizeGreaterThanOrEqualTo(16);
            assertThat(service.passwordMatches(temporary)).isTrue();
        }
    }

    @Test
    @DisplayName("충분히 긴 설정 키는 그대로 쓴다 (재시작해도 세션 유지)")
    void strongSecretIsStable() {
        byte[] key = AuthService.signingSecret("test-secret-key-that-is-long-enough-32b");
        assertThat(new String(key, java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("test-secret-key-that-is-long-enough-32b");
    }
}
