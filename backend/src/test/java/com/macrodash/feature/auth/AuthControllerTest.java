package com.macrodash.feature.auth;

import com.macrodash.config.AppProperties;
import com.macrodash.support.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerTest {

    private final AuthTestDatabase db = new AuthTestDatabase("authctl");
    private final AppProperties properties = new AppProperties();
    private final AuthController controller;

    AuthControllerTest() {
        db.user("owner", "right-password-1", "OWNER", "PRO");
        // 느린 차선의 대기를 실제로 기다리지 않습니다(동작은 LoginThrottleTest가 고정).
        controller = new AuthController(db.service(properties),
                new LoginThrottle(java.time.Clock.systemUTC(), millis -> { }), properties);
    }

    @AfterEach
    void cleanUp() {
        db.cleanUp();
    }

    private ResponseEntity<Map<String, Object>> login(String email, String password, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        return controller.login(new AuthController.LoginRequest(email, password), request);
    }

    private String token(ResponseEntity<Map<String, Object>> response) {
        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return cookie.split(";")[0].split("=", 2)[1];
    }

    @Test
    @DisplayName("세션 쿠키는 HttpOnly + SameSite=Strict, 응답에 사용자(이메일·역할·등급)")
    void cookieAttributes() {
        ResponseEntity<Map<String, Object>> response = login(db.email("owner"), "right-password-1", "10.0.0.1");

        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookie).contains("HttpOnly").contains("SameSite=Strict").doesNotContain("Secure");
        assertThat(response.getBody()).containsEntry("ok", true);
        assertThat(String.valueOf(((Map<?, ?>) response.getBody().get("user")).get("role"))).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("연속 실패하면 429 + Retry-After, 그래도 맞는 비밀번호는 통과한다")
    void bruteForceIsThrottledButOwnerStillGetsIn() {
        String email = db.email("owner");
        for (int i = 0; i < 5; i++) {   // 무료 시도 5회 (LoginThrottle.FREE_ATTEMPTS)
            assertThat(login(email, "wrong", "10.0.0.2").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<Map<String, Object>> slowed = login(email, "wrong", "10.0.0.2");
        assertThat(slowed.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(slowed.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");

        assertThat(login(email, "right-password-1", "10.0.0.2").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(email, "right-password-1", "10.0.0.3").getStatusCode())
                .as("다른 주소는 영향 없음").isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("세션 확인은 로그인돼 있을 때만 사용자·읽기 모드를 함께 준다")
    void sessionCarriesUserOnlyWhenAuthenticated() {
        properties.setReadMode("store_only");
        assertThat(controller.session(new MockHttpServletRequest()))
                .containsEntry("authenticated", false)
                .doesNotContainKey("readMode").doesNotContainKey("user");

        MockHttpServletRequest signedIn = new MockHttpServletRequest();
        signedIn.setCookies(new jakarta.servlet.http.Cookie(AuthService.COOKIE_NAME,
                token(login(db.email("owner"), "right-password-1", "10.0.0.9"))));
        Map<String, Object> session = controller.session(signedIn);
        assertThat(session).containsEntry("authenticated", true).containsEntry("readMode", "store_only");
        assertThat(String.valueOf(((Map<?, ?>) session.get("user")).get("email"))).isEqualTo(db.email("owner"));
    }

    @Test
    @DisplayName("비밀번호 변경: 규칙에 어긋나면 400, 맞으면 200")
    void changePassword() {
        String token = token(login(db.email("owner"), "right-password-1", "10.0.0.5"));
        AuthService auth = db.service(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUser.ATTRIBUTE, auth.authenticate(token).orElseThrow());

        assertThat(controller.changePassword(new AuthController.PasswordRequest("right-password-1", "short"), request)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.changePassword(
                new AuthController.PasswordRequest("right-password-1", "a much longer passphrase"), request)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("본문 없는 로그인 요청은 500이 아니라 401")
    void missingBodyIs401() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.4");
        assertThat(controller.login(null, request).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
