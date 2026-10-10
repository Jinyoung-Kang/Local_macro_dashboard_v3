package com.macrodash.config;

import com.macrodash.feature.auth.AuthService;
import com.macrodash.feature.auth.DemoViewer;
import com.macrodash.feature.auth.PasswordHasher;
import com.macrodash.store.AuditLogStore;
import com.macrodash.store.SessionStore;
import com.macrodash.store.UserStore;
import com.macrodash.support.CurrentUser;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionFilterTest {

    private static MockHttpServletRequest request(String servletPath) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", servletPath);
        request.setServletPath(servletPath);
        return request;
    }

    @Test
    @DisplayName("공개 경로는 정확히 일치할 때만 인증을 건너뛴다")
    void publicPathsMatchExactly() {
        assertThat(WebConfig.SessionFilter.isPublic(request("/api/health"))).isTrue();
        assertThat(WebConfig.SessionFilter.isPublic(request("/api/auth/login"))).isTrue();

        // 예전 접두사 비교에서는 아래가 모두 공개로 판정됐습니다.
        assertThat(WebConfig.SessionFilter.isPublic(request("/api/healthx"))).isFalse();
        assertThat(WebConfig.SessionFilter.isPublic(request("/api/health/details"))).isFalse();
        assertThat(WebConfig.SessionFilter.isPublic(request("/api/auth/login/extra"))).isFalse();
        assertThat(WebConfig.SessionFilter.isPublic(request("/api/auth/logout"))).isFalse();
    }

    @Test
    @DisplayName("로그인 본문은 4KB까지만 읽고, 넘으면 파싱 전에 413으로 끝낸다")
    void oversizedLoginIsRejectedBeforeParsing() throws Exception {
        WebConfig.SessionFilter filter = new WebConfig.SessionFilter(null, java.util.List.of("http://*:3000"));

        MockHttpServletRequest huge = new MockHttpServletRequest("POST", "/api/auth/login");
        huge.setServletPath("/api/auth/login");
        huge.setContent(new byte[(int) WebConfig.SessionFilter.MAX_LOGIN_BODY_BYTES + 1]);
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        boolean[] reached = {false};
        filter.doFilter(huge, rejected, (req, res) -> reached[0] = true);
        assertThat(rejected.getStatus()).isEqualTo(413);
        assertThat(reached[0]).as("컨트롤러까지 가지 않음").isFalse();

        // 길이를 밝히지 않은(chunked) 요청도 읽어 보고 같은 상한을 적용합니다.
        MockHttpServletRequest small = new MockHttpServletRequest("POST", "/api/auth/login");
        small.setServletPath("/api/auth/login");
        small.setContent("{\"password\":\"pw\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String[] seen = {null};
        filter.doFilter(small, new MockHttpServletResponse(), (req, res) ->
                seen[0] = new String(req.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        assertThat(seen[0]).as("상한 안의 본문은 그대로 전달").isEqualTo("{\"password\":\"pw\"}");

        assertThat(WebConfig.SessionFilter.isLoginPost(request("/api/ai/report"))).as("다른 경로는 건드리지 않음").isFalse();
    }

    @Test
    @DisplayName("API 응답에 보안 헤더가 붙는다")
    void securityHeaders() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        WebConfig.SessionFilter.addSecurityHeaders(response);

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
    }

    @Test
    @DisplayName("다른 출처의 상태 변경 요청은 막고, Origin 없는 요청(스크립트)과 같은 출처·GET은 통과")
    void foreignOriginIsDetected() {
        WebConfig.SessionFilter filter = new WebConfig.SessionFilter(null, java.util.List.of("http://*:3000"));
        MockHttpServletRequest evil = new MockHttpServletRequest("POST", "/api/auth/logout");
        evil.addHeader("Origin", "https://evil.example");
        MockHttpServletRequest lan = new MockHttpServletRequest("POST", "/api/auth/logout");
        lan.addHeader("Origin", "http://192.168.0.10:3000");
        MockHttpServletRequest script = new MockHttpServletRequest("POST", "/api/auth/logout");
        MockHttpServletRequest read = new MockHttpServletRequest("GET", "/api/macro/overview");
        read.addHeader("Origin", "https://evil.example");

        assertThat(filter.foreignOrigin(evil)).isTrue();
        assertThat(filter.foreignOrigin(lan)).isFalse();
        assertThat(filter.foreignOrigin(script)).isFalse();
        assertThat(filter.foreignOrigin(read)).isFalse();
    }

    @Test
    @DisplayName("필터의 공개 경로가 접근 정책 표의 공개 경로와 같다 — 두 곳이 어긋나면 인증 없이 열리는 경로가 생긴다")
    void publicPathsMatchAccessPolicy() {
        java.util.Set<String> fromPolicy = new java.util.TreeSet<>();
        com.macrodash.feature.auth.AccessPolicy.routes().forEach((route, access) -> {
            if (access == com.macrodash.feature.auth.AccessPolicy.Access.PUBLIC) {
                fromPolicy.add(route.split(" ", 2)[1]);
            }
        });
        assertThat(new java.util.TreeSet<>(WebConfig.SessionFilter.PUBLIC_PATHS)).isEqualTo(fromPolicy);
    }

    /**
     * 데모 열람(ADR 0013) — 앞단 프록시가 접근 코드를 확인한 요청에 붙이는 {@code X-Demo-Viewer} 헤더.
     * 세션 저장소는 흉내(쿠키 {@code owner-cookie}·{@code member-cookie}만 유효)이고, 필터·AuthService는 실제 코드입니다.
     */
    @Nested
    @DisplayName("데모 열람")
    class DemoViewerMode {

        static final String TOKEN = "test-demo-viewer-token-0123456789abcdef";
        static final UUID OWNER_SESSION = UUID.randomUUID();
        static final UUID MEMBER_SESSION = UUID.randomUUID();

        /** 필터를 거친 결과 — 상태·본문, 컨트롤러까지 갔는지, 그때의 사용자. */
        record Outcome(int status, String body, boolean reached, CurrentUser user) {
        }

        private WebConfig.SessionFilter filter(String configuredToken) {
            AppProperties properties = new AppProperties();
            properties.setDemoViewerToken(configuredToken);
            UserStore users = mock(UserStore.class);
            when(users.ready()).thenReturn(true);
            SessionStore sessions = mock(SessionStore.class);
            when(sessions.findActive(any())).thenAnswer(invocation -> {
                byte[] hash = invocation.getArgument(0);
                Instant later = Instant.now().plusSeconds(3600);
                if (Arrays.equals(hash, sha256("owner-cookie"))) {
                    return Optional.of(new SessionStore.Active(OWNER_SESSION, 1, "owner@test.local", "OWNER", "PRO",
                            Instant.now(), later));
                }
                if (Arrays.equals(hash, sha256("member-cookie"))) {
                    return Optional.of(new SessionStore.Active(MEMBER_SESSION, 2, "member@test.local", "MEMBER", "FREE",
                            Instant.now(), later));
                }
                return Optional.empty();
            });
            AuthService auth = new AuthService(properties, users, sessions, mock(AuditLogStore.class), new PasswordHasher());
            return new WebConfig.SessionFilter(auth, List.of("http://*:3000"));
        }

        private Outcome send(WebConfig.SessionFilter filter, String method, String path, String header, String cookie)
                throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest(method, path);
            request.setServletPath(path);
            if (header != null) {
                request.addHeader(DemoViewer.HEADER, header);
            }
            if (cookie != null) {
                request.setCookies(new Cookie(AuthService.COOKIE_NAME, cookie));
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            CurrentUser[] seen = {null};
            boolean[] reached = {false};
            filter.doFilter(request, response, (req, res) -> {
                reached[0] = true;
                seen[0] = CurrentUser.of((jakarta.servlet.http.HttpServletRequest) req);
            });
            return new Outcome(response.getStatus(), response.getContentAsString(StandardCharsets.UTF_8), reached[0],
                    seen[0]);
        }

        @Test
        @DisplayName("QA-020 로그인했거나 데모인 쓰기 요청도 본문이 64KB를 넘으면 파싱 전에 413")
        void oversizedAuthenticatedWriteIsRejected() throws Exception {
            WebConfig.SessionFilter on = filter(TOKEN);
            byte[] huge = new byte[128 * 1024];
            Arrays.fill(huge, (byte) 'A');

            MockHttpServletRequest demo = new MockHttpServletRequest("POST", "/api/ai/report");
            demo.setServletPath("/api/ai/report");
            demo.addHeader(DemoViewer.HEADER, TOKEN);
            demo.setContent(huge);
            MockHttpServletResponse demoResponse = new MockHttpServletResponse();
            boolean[] reached = {false};
            on.doFilter(demo, demoResponse, (req, res) -> reached[0] = true);
            assertThat(demoResponse.getStatus()).isEqualTo(413);
            assertThat(reached[0]).as("데모 방문자의 큰 본문이 컨트롤러까지 가지 않음").isFalse();

            MockHttpServletRequest member = new MockHttpServletRequest("POST", "/api/alerts/rules");
            member.setServletPath("/api/alerts/rules");
            member.setCookies(new Cookie(AuthService.COOKIE_NAME, "member-cookie"));
            member.setContent(huge);
            MockHttpServletResponse memberResponse = new MockHttpServletResponse();
            on.doFilter(member, memberResponse, (req, res) -> reached[0] = true);
            assertThat(memberResponse.getStatus()).isEqualTo(413);
            assertThat(reached[0]).isFalse();
        }

        @Test
        @DisplayName("기능이 꺼져 있으면(기본) 헤더를 보지 않는다 — 같은 값을 보내도 401")
        void headerIgnoredWhenOff() throws Exception {
            WebConfig.SessionFilter off = filter("");
            Outcome outcome = send(off, "GET", "/api/macro/overview", TOKEN, null);
            assertThat(outcome.status()).isEqualTo(401);
            assertThat(outcome.reached()).isFalse();
            assertThat(send(off, "POST", "/api/alerts/rules", TOKEN, null).status())
                    .as("쓰기도 데모 403이 아니라 평소의 401").isEqualTo(401);
        }

        @Test
        @DisplayName("틀린 값·빈 값이면 헤더가 없는 것과 같다 — 401")
        void wrongTokenIsUnauthorized() throws Exception {
            WebConfig.SessionFilter on = filter(TOKEN);
            for (String wrong : new String[]{"wrong-token", "", TOKEN.substring(0, 32), TOKEN + " x"}) {
                Outcome outcome = send(on, "GET", "/api/macro/overview", wrong, null);
                assertThat(outcome.status()).as(wrong).isEqualTo(401);
                assertThat(outcome.reached()).isFalse();
            }
            assertThat(send(on, "GET", "/api/macro/overview", null, null).status()).as("헤더 없음").isEqualTo(401);
        }

        @Test
        @DisplayName("맞는 값이면 일반 회원·유료 등급의 데모 사용자로 GET을 통과한다(회원 경로·유료 경로)")
        void rightTokenReadsMemberAndProRoutes() throws Exception {
            WebConfig.SessionFilter on = filter(TOKEN);
            for (String path : new String[]{"/api/macro/overview", "/api/alerts/inbox", "/api/kr/stock-flows",
                    "/api/guru/risk", "/api/sec13f/new-buys"}) {
                Outcome outcome = send(on, "GET", path, TOKEN, null);
                assertThat(outcome.reached()).as(path).isTrue();
                assertThat(outcome.user().demo()).isTrue();
                assertThat(outcome.user().id()).isEqualTo(DemoViewer.USER_ID);
                assertThat(outcome.user().role()).isEqualTo("MEMBER");
                assertThat(outcome.user().tier()).isEqualTo("PRO");
            }
        }

        @Test
        @DisplayName("운영자 경로는 데모 사용자에게 403 required=OWNER — 접근 정책 표 그대로")
        void ownerRoutesStayForbidden() throws Exception {
            WebConfig.SessionFilter on = filter(TOKEN);
            for (String path : new String[]{"/api/status", "/api/admin/users", "/api/radar/diagnostics"}) {
                Outcome outcome = send(on, "GET", path, TOKEN, null);
                assertThat(outcome.status()).as(path).isEqualTo(403);
                assertThat(outcome.body()).contains("\"required\":\"OWNER\"");
                assertThat(outcome.reached()).isFalse();
            }
        }

        @Test
        @DisplayName("데모 사용자는 GET·OPTIONS 밖(쓰기·HEAD)이 경로와 상관없이 403 demo_read_only — POST /api/ai/report 하나만 예외")
        void writesAreReadOnly() throws Exception {
            WebConfig.SessionFilter on = filter(TOKEN);
            String[][] writes = {{"POST", "/api/alerts/rules"}, {"POST", "/api/alerts/rules/1"},
                    {"DELETE", "/api/alerts/rules/1"}, {"POST", "/api/alerts/inbox/read"},
                    {"POST", "/api/auth/logout"}, {"POST", "/api/auth/login"}, {"POST", "/api/auth/signup"},
                    {"PUT", "/api/alerts/rules/1"}, {"PATCH", "/api/alerts/rules/1"}, {"POST", "/api/status/refresh"},
                    // AI 리포트 예외(ADR 0014)는 정확히 POST /api/ai/report만 — 같은 경로의 다른 메서드·비슷한 경로·연결 시험은 그대로
                    {"PUT", "/api/ai/report"}, {"DELETE", "/api/ai/report"}, {"POST", "/api/ai/report/1"},
                    {"POST", "/api/ai/test"}};
            for (String[] write : writes) {
                Outcome outcome = send(on, write[0], write[1], TOKEN, null);
                assertThat(outcome.status()).as(write[0] + " " + write[1]).isEqualTo(403);
                assertThat(outcome.body()).contains("demo_read_only").contains("데모에서는 조회만 가능합니다");
                assertThat(outcome.reached()).isFalse();
            }
            assertThat(send(on, "OPTIONS", "/api/alerts/rules", TOKEN, null).reached())
                    .as("CORS 사전 요청은 통과").isTrue();
            // HEAD는 스프링이 GET 처리기로 돌리는데 정책 표에 HEAD 줄이 없어 운영자 경로가 열립니다 — 데모는 막습니다.
            Outcome head = send(on, "HEAD", "/api/status", TOKEN, null);
            assertThat(head.status()).as("HEAD").isEqualTo(403);
            assertThat(head.reached()).isFalse();
        }

        @Test
        @DisplayName("데모 사용자의 POST /api/ai/report는 데모 사용자로 컨트롤러까지 간다(ADR 0014 — 공용 하루 한도는 서비스가 셈)")
        void aiReportIsTheOnlyDemoWrite() throws Exception {
            WebConfig.SessionFilter on = filter(TOKEN);
            Outcome outcome = send(on, "POST", "/api/ai/report", TOKEN, null);
            assertThat(outcome.reached()).isTrue();
            assertThat(outcome.user().demo()).isTrue();
            assertThat(outcome.user().id()).isEqualTo(DemoViewer.USER_ID);

            Outcome head = send(on, "HEAD", "/api/ai/report", TOKEN, null);
            assertThat(head.status()).as("HEAD").isEqualTo(403);
            assertThat(head.reached()).isFalse();
            assertThat(send(off(), "POST", "/api/ai/report", TOKEN, null).status())
                    .as("기능이 꺼져 있으면 평소처럼 401").isEqualTo(401);
        }

        private WebConfig.SessionFilter off() {
            return filter("");
        }

        @Test
        @DisplayName("공개 GET(세션 확인·헬스)은 헤더가 있어도 그대로 통과한다")
        void publicReadsPassThrough() throws Exception {
            WebConfig.SessionFilter on = filter(TOKEN);
            assertThat(send(on, "GET", "/api/auth/session", TOKEN, null).reached()).isTrue();
            assertThat(send(on, "GET", "/api/health", TOKEN, null).reached()).isTrue();
        }

        @Test
        @DisplayName("유효한 세션 쿠키가 있으면 세션이 이긴다 — 데모로 낮추지도, 쓰기를 막지도 않는다")
        void sessionTakesPrecedence() throws Exception {
            WebConfig.SessionFilter on = filter(TOKEN);

            Outcome owner = send(on, "GET", "/api/status", TOKEN, "owner-cookie");
            assertThat(owner.reached()).as("운영자 세션 + 헤더 → 운영 경로 통과").isTrue();
            assertThat(owner.user().demo()).isFalse();
            assertThat(owner.user().sessionId()).isEqualTo(OWNER_SESSION);

            Outcome member = send(on, "GET", "/api/kr/stock-flows", TOKEN, "member-cookie");
            assertThat(member.status()).as("무료 회원 세션이면 헤더가 있어도 무료 회원 — 유료 경로 403").isEqualTo(403);
            assertThat(member.body()).contains("\"required\":\"PRO\"");

            Outcome write = send(on, "POST", "/api/alerts/rules", TOKEN, "member-cookie");
            assertThat(write.reached()).as("세션이 있으면 쓰기도 평소대로").isTrue();
            assertThat(write.user().demo()).isFalse();

            Outcome expired = send(on, "POST", "/api/alerts/rules", TOKEN, "expired-cookie");
            assertThat(expired.status()).as("만료·해지된 쿠키는 세션이 아님 → 데모 쓰기 403").isEqualTo(403);
            assertThat(expired.body()).contains("demo_read_only");
            assertThat(send(on, "GET", "/api/macro/overview", TOKEN, "expired-cookie").user().demo())
                    .as("만료된 쿠키 + 헤더 GET → 데모 열람").isTrue();
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
