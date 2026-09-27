package com.macrodash.web;

import com.macrodash.config.AppProperties;
import com.macrodash.service.AuthService;
import com.macrodash.service.LoginThrottle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerTest {

    private final AppProperties properties = new AppProperties();
    private final AuthController controller;

    AuthControllerTest() {
        properties.setPassword("right-password");
        properties.setJwtSecret("controller-test-secret-key-32-bytes!!");
        controller = new AuthController(new AuthService(properties), new LoginThrottle(), properties);
    }

    private ResponseEntity<Map<String, Object>> login(String password, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        return controller.login(new AuthController.LoginRequest(password), request);
    }

    @Test
    @DisplayName("세션 쿠키는 HttpOnly + SameSite=Strict")
    void cookieAttributes() {
        ResponseEntity<Map<String, Object>> response = login("right-password", "10.0.0.1");

        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookie).contains("HttpOnly").contains("SameSite=Strict").doesNotContain("Secure");
    }

    @Test
    @DisplayName("연속 실패하면 429 + Retry-After, 잠긴 동안에는 맞는 비밀번호도 거절")
    void bruteForceIsThrottled() {
        for (int i = 0; i < 5; i++) {   // 무료 시도 5회 (LoginThrottle.FREE_ATTEMPTS)
            assertThat(login("wrong", "10.0.0.2").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<Map<String, Object>> locked = login("wrong", "10.0.0.2");
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(locked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");

        assertThat(login("right-password", "10.0.0.2").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(login("right-password", "10.0.0.3").getStatusCode())
                .as("다른 주소는 영향 없음").isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("세션 확인은 로그인돼 있을 때만 읽기 모드를 함께 준다")
    void sessionCarriesReadModeOnlyWhenAuthenticated() {
        properties.setReadMode("store_only");
        MockHttpServletRequest anonymous = new MockHttpServletRequest();
        assertThat(controller.session(anonymous))
                .containsEntry("authenticated", false)
                .doesNotContainKey("readMode");

        String cookie = login("right-password", "10.0.0.9").getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        MockHttpServletRequest signedIn = new MockHttpServletRequest();
        signedIn.setCookies(new jakarta.servlet.http.Cookie(
                AuthService.COOKIE_NAME, cookie.split(";")[0].split("=", 2)[1]));
        assertThat(controller.session(signedIn))
                .containsEntry("authenticated", true)
                .containsEntry("readMode", "store_only");
    }

    @Test
    @DisplayName("본문 없는 로그인 요청은 500이 아니라 401")
    void missingBodyIs401() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.4");
        assertThat(controller.login(null, request).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
