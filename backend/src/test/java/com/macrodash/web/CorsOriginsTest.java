package com.macrodash.web;

import com.macrodash.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    @Test
    @DisplayName("QA-136: AWS 배포(compose.aws.yml)의 허용 출처가 관리자 터널(SSM 포트 포워딩, localhost:8201) 화면의 POST를 받는다")
    void awsDeploymentAcceptsTheAdminTunnel() throws Exception {
        Path compose = Path.of("../compose.aws.yml");
        assumeTrue(Files.exists(compose), "compose.aws.yml을 찾을 수 없습니다");
        Matcher setting = Pattern.compile("\\n\\s+FRONTEND_ORIGIN:\\s*(.+)").matcher(Files.readString(compose));
        assertThat(setting.find()).as("compose.aws.yml backend에 FRONTEND_ORIGIN이 있어야 함").isTrue();
        // 배포 때 .env의 PUBLIC_ORIGIN(CloudFront 주소)이 들어가는 자리입니다.
        String origins = setting.group(1).trim().replaceAll("^\"|\"$", "")
                .replaceAll("\\$\\{PUBLIC_ORIGIN[^}]*}", "https://dxxxx.cloudfront.net");

        AppProperties properties = new AppProperties();
        properties.setFrontendOrigin(origins);
        properties.setFrontendPort(3000);
        WebConfig.SessionFilter filter = new WebConfig.SessionFilter(null, WebConfig.allowedOriginPatterns(properties), false);

        for (String origin : new String[]{"https://dxxxx.cloudfront.net", "http://localhost:8201", "http://127.0.0.1:8201"}) {
            assertThat(filter.foreignOrigin(post(origin))).as(origin).isFalse();
        }
        for (String origin : new String[]{"https://attacker.example", "http://localhost:8202", "https://localhost:8201"}) {
            assertThat(filter.foreignOrigin(post(origin))).as(origin).isTrue();
        }
    }

    private static MockHttpServletRequest post(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/alerts/rules");
        request.addHeader("Origin", origin);
        return request;
    }
}
