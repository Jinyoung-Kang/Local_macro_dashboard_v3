package com.macrodash.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

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
        WebConfig.SessionFilter filter = new WebConfig.SessionFilter(null);

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
}
