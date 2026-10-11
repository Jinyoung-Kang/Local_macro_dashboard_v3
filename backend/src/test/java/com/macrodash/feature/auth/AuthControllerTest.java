package com.macrodash.feature.auth;

import com.macrodash.config.AppProperties;
import com.macrodash.support.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerTest {

    private final AuthTestDatabase db = new AuthTestDatabase("authctl");
    private final AppProperties properties = new AppProperties();
    private final AuthController controller;
    private final AccountService accounts;

    AuthControllerTest() {
        db.user("owner", "right-password-1", "OWNER", "PRO");
        // 느린 차선의 대기를 실제로 기다리지 않습니다(동작은 LoginThrottleTest가 고정).
        AccountMail mail = org.mockito.Mockito.mock(AccountMail.class);
        accounts = new AccountService(db.users, db.sessions,
                new com.macrodash.store.EmailTokenStore(db.jdbc), db.audit, db.hasher, mail);
        controller = new AuthController(db.service(properties), accounts,
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
    @DisplayName("QA-024 비밀번호 변경·탈퇴의 '지금 비밀번호' 확인도 연속 실패하면 느려지고 429 — 훔친 세션으로 대입하지 못하게")
    void reauthenticationIsThrottled() {
        long memberId = db.user("member", "member-password-1", "MEMBER", "FREE");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        request.setAttribute(CurrentUser.ATTRIBUTE,
                new CurrentUser(memberId, db.email("member"), "MEMBER", "FREE", null, false));

        for (int i = 0; i < 5; i++) {   // 무료 시도 5회 (LoginThrottle.FREE_ATTEMPTS)
            assertThat(controller.changePassword(new AuthController.PasswordRequest("wrong", "new-password-12345"), request)
                    .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(controller.changePassword(new AuthController.PasswordRequest("wrong", "new-password-12345"), request)
                .getStatusCode()).as("비밀번호 변경").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(controller.deleteAccount(new AuthController.DeleteRequest("wrong"), request).getStatusCode())
                .as("탈퇴도 같은 사용자 기록").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(controller.changePassword(new AuthController.PasswordRequest("member-password-1", "new-password-12345"),
                request).getStatusCode()).as("맞는 비밀번호는 그래도 통과").isEqualTo(HttpStatus.OK);
    }

    @Test
    @Disabled("QA-068: 재확인이 로그인과 느린 차선을 함께 씀 — 수정 커밋에서 켬")
    @DisplayName("QA-068 비밀번호 재확인은 로그인과 느린 차선을 나눠 쓴다 — 로그인 대입 중에도 재확인이 '처리 중' 429를 받지 않는다")
    void reauthenticationHasItsOwnSlowLane() throws Exception {
        long memberId = db.user("member", "member-password-1", "MEMBER", "FREE");
        CountDownLatch inSlowLane = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread[] loginThread = new Thread[1];
        LoginThrottle throttle = new LoginThrottle(java.time.Clock.systemUTC(), millis -> {
            if (Thread.currentThread() == loginThread[0]) {   // 로그인 쪽 느린 시도만 차선을 붙잡고 기다린다
                inSlowLane.countDown();
                release.await(5, TimeUnit.SECONDS);
            }
        });
        AuthController separate = new AuthController(db.service(properties), accounts, throttle, properties);
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        loginRequest.setRemoteAddr("10.0.0.7");
        for (int i = 0; i < 5; i++) {
            separate.login(new AuthController.LoginRequest(db.email("owner"), "wrong"), loginRequest);
        }
        loginThread[0] = new Thread(() ->
                separate.login(new AuthController.LoginRequest(db.email("owner"), "wrong"), loginRequest));
        loginThread[0].start();
        assertThat(inSlowLane.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setAttribute(CurrentUser.ATTRIBUTE,
                    new CurrentUser(memberId, db.email("member"), "MEMBER", "FREE", null, false));
            for (int i = 0; i < 5; i++) {
                separate.changePassword(new AuthController.PasswordRequest("wrong", "new-password-12345"), request);
            }
            ResponseEntity<Map<String, Object>> slowed =
                    separate.changePassword(new AuthController.PasswordRequest("wrong", "new-password-12345"), request);

            assertThat(String.valueOf(slowed.getBody().get("message"))).doesNotContain("다른 확인 요청");
        } finally {
            release.countDown();
            loginThread[0].join(5000);
        }
    }

    @Test
    @Disabled("QA-068: 재확인 한 번에 지금 비밀번호를 두 번 해시 — 수정 커밋에서 켬")
    @DisplayName("QA-068 비밀번호 변경·탈퇴는 지금 비밀번호를 한 번만 확인한다(Argon2 16MB를 요청마다 한 번 더 돌리지 않게)")
    void currentPasswordIsVerifiedOnce() {
        long memberId = db.user("member", "member-password-1", "MEMBER", "FREE");
        AtomicInteger checks = new AtomicInteger();
        PasswordEncoder argon = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        PasswordHasher counting = new PasswordHasher(new PasswordEncoder() {
            @Override
            public String encode(CharSequence raw) {
                return argon.encode(raw);
            }

            @Override
            public boolean matches(CharSequence raw, String encoded) {
                checks.incrementAndGet();
                return argon.matches(raw, encoded);
            }
        });
        AccountMail mail = org.mockito.Mockito.mock(AccountMail.class);
        AuthController counted = new AuthController(
                new AuthService(properties, db.users, db.sessions, db.audit, counting),
                new AccountService(db.users, db.sessions, new com.macrodash.store.EmailTokenStore(db.jdbc), db.audit,
                        counting, mail),
                new LoginThrottle(java.time.Clock.systemUTC(), millis -> { }), properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUser.ATTRIBUTE,
                new CurrentUser(memberId, db.email("member"), "MEMBER", "FREE", null, false));

        assertThat(counted.deleteAccount(new AuthController.DeleteRequest("wrong"), request).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(checks.get()).as("탈퇴: 지금 비밀번호 확인 한 번").isEqualTo(1);

        checks.set(0);
        assertThat(counted.changePassword(new AuthController.PasswordRequest("member-password-1", "new-password-12345"),
                request).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(checks.get()).as("변경: 지금 비밀번호 한 번 + 새 비밀번호가 같은지 한 번").isEqualTo(2);
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
    @DisplayName("데모 열람(ADR 0013): 세션 없이 맞는 헤더면 authenticated + user.demo(일반 회원·유료, 이메일 없음), 세션이 있으면 세션")
    void sessionReportsDemoViewer() {
        AppProperties demoProperties = new AppProperties();
        demoProperties.setReadMode("store_only");
        demoProperties.setDemoViewerToken(DemoViewerTest.TOKEN);
        AuthController demo = new AuthController(db.service(demoProperties), accounts,
                new LoginThrottle(java.time.Clock.systemUTC(), millis -> { }), demoProperties);

        MockHttpServletRequest visitor = new MockHttpServletRequest();
        visitor.addHeader(DemoViewer.HEADER, DemoViewerTest.TOKEN);
        Map<String, Object> session = demo.session(visitor);
        assertThat(session).containsEntry("authenticated", true).containsEntry("readMode", "store_only");
        Map<?, ?> user = (Map<?, ?>) session.get("user");
        assertThat(user.get("demo")).isEqualTo(true);
        assertThat(user.get("role")).isEqualTo("MEMBER");
        assertThat(user.get("tier")).isEqualTo("PRO");
        assertThat(user.containsKey("email")).as("이메일 칸은 있되 값이 없음(자리표시 이메일을 만들지 않음)").isTrue();
        assertThat(user.get("email")).isNull();

        MockHttpServletRequest wrong = new MockHttpServletRequest();
        wrong.addHeader(DemoViewer.HEADER, "not-the-token");
        assertThat(demo.session(wrong)).containsEntry("authenticated", false).doesNotContainKey("user");
        MockHttpServletRequest offHeader = new MockHttpServletRequest();
        offHeader.addHeader(DemoViewer.HEADER, DemoViewerTest.TOKEN);
        assertThat(controller.session(offHeader)).as("기능이 꺼진 컨트롤러는 헤더를 보지 않음")
                .containsEntry("authenticated", false);

        MockHttpServletRequest owner = new MockHttpServletRequest();
        owner.addHeader(DemoViewer.HEADER, DemoViewerTest.TOKEN);
        owner.setCookies(new jakarta.servlet.http.Cookie(AuthService.COOKIE_NAME,
                token(login(db.email("owner"), "right-password-1", "10.0.0.10"))));
        Map<?, ?> ownerUser = (Map<?, ?>) demo.session(owner).get("user");
        assertThat(ownerUser.get("demo")).as("세션이 이김").isEqualTo(false);
        assertThat(ownerUser.get("role")).isEqualTo("OWNER");
        assertThat(ownerUser.get("email")).isEqualTo(db.email("owner"));
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
