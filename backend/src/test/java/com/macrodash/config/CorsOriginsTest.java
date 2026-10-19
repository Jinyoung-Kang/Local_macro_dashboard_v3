package com.macrodash.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class CorsOriginsTest {

    @Test
    @DisplayName("명시한 origin에 더해 화면 포트의 모든 호스트를 허용한다 — 휴대폰이 LAN IP로 연다")
    void allowsAnyHostOnTheFrontendPort() {
        AppProperties properties = new AppProperties();
        properties.setFrontendOrigin("http://localhost:3000, http://dashboard.local");
        properties.setFrontendPort(3000);

        assertThat(WebConfig.allowedOriginPatterns(properties))
                .containsExactly("http://localhost:3000", "http://dashboard.local", "http://*:3000");
    }

    @Test
    @DisplayName("로컬 무로그인(ADR 0016)이면 이 맥의 화면 주소 셋만 — 와일드카드(http://*:포트)와 LAN 주소 없음")
    void localNoLoginAllowsOnlyThisMac() {
        AppProperties properties = new AppProperties();
        properties.setLocalNoLogin(true);
        properties.setFrontendOrigin("http://localhost:3001");
        properties.setFrontendPort(3001);

        assertThat(WebConfig.allowedOriginPatterns(properties))
                .containsExactly("http://localhost:3001", "http://127.0.0.1:3001", "http://[::1]:3001");

        // 필터의 출처 검사(foreignOrigin)도 같은 목록을 씁니다 — [::1]이 포트 목록 문법([8080,8081])으로 읽히지 않는지까지.
        WebConfig.SessionFilter filter = new WebConfig.SessionFilter(null, WebConfig.allowedOriginPatterns(properties), true);
        for (String origin : new String[]{"http://localhost:3001", "http://127.0.0.1:3001", "http://[::1]:3001"}) {
            assertThat(filter.foreignOrigin(post(origin))).as(origin).isFalse();
        }
        for (String origin : new String[]{"http://192.168.0.10:3001", "http://localhost:3000", "https://localhost:3001",
                "http://[::2]:3001", "http://dashboard.local"}) {
            assertThat(filter.foreignOrigin(post(origin))).as(origin).isTrue();
        }
    }

    private static MockHttpServletRequest post(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/alerts/rules");
        request.addHeader("Origin", origin);
        return request;
    }
}
