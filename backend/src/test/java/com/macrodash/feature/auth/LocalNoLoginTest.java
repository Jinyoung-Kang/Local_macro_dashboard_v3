package com.macrodash.feature.auth;

import com.macrodash.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 로컬 무로그인(ADR 0016) — 웹 배포 설정과 섞이면 기동을 멈추고, '이 맥에서 바로 온 요청'만 가려낸다. */
class LocalNoLoginTest {

    /** 로컬 docker-compose.yml의 기본값과 같은 설정(무로그인 켬, 웹 배포 신호 없음). */
    private static AppProperties local() {
        AppProperties properties = new AppProperties();
        properties.setLocalNoLogin(true);
        properties.setFrontendOrigin("http://localhost:3000");
        properties.setFrontendPort(3000);
        return properties;
    }

    private static AppProperties with(Consumer<AppProperties> change) {
        AppProperties properties = local();
        change.accept(properties);
        return properties;
    }

    @Test
    @DisplayName("켜져 있고 웹 배포 신호가 없으면 기동한다 — 화면 주소는 이 맥의 http 주소 여럿·포트 없음·끝의 / 허용")
    void cleanLocalConfigurationStarts() {
        assertThat(new LocalNoLogin(local(), "none").enabled()).isTrue();
        for (String strategy : new String[]{null, "", " ", "NONE", "none"}) {
            assertThatCode(() -> LocalNoLogin.check(local(), strategy)).as(strategy).doesNotThrowAnyException();
        }
        for (String origins : new String[]{"http://localhost:3000, http://127.0.0.1:3000,http://[::1]:3000",
                "http://localhost", "http://LOCALHOST:3001/", "", " , "}) {
            assertThatCode(() -> LocalNoLogin.check(with(p -> p.setFrontendOrigin(origins)), "none"))
                    .as(origins).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("켜져 있을 때 AWS 배포의 신호가 하나만 있어도 기동하지 않는다 — 데모 토큰·Secure 쿠키·앞단 헤더 신뢰·밖의 화면 주소")
    void eachWebSignalAloneRefusesStartup() {
        String token = DemoViewerTest.TOKEN;
        assertThatThrownBy(() -> new LocalNoLogin(with(p -> p.setDemoViewerToken(token)), "none"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DASHBOARD_DEMO_VIEWER_TOKEN")
                .hasMessageContaining("LOCAL_NO_LOGIN=false")
                .hasMessageNotContaining(token);
        assertThatThrownBy(() -> LocalNoLogin.check(with(p -> p.setCookieSecure(true)), "none"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("COOKIE_SECURE");
        for (String strategy : new String[]{"native", "NATIVE", "framework"}) {
            assertThatThrownBy(() -> LocalNoLogin.check(local(), strategy)).as(strategy)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("forward-headers-strategy");
        }
        for (String origin : new String[]{"https://dxxxx.cloudfront.net", "http://192.168.0.10:3000",
                "https://localhost:3000", "http://localhost.evil.example:3000", "http://user@localhost:3000",
                "http://localhost:3000/app", "http://localhost:3000, http://dashboard.local", "not a url"}) {
            assertThatThrownBy(() -> LocalNoLogin.check(with(p -> p.setFrontendOrigin(origin)), "none")).as(origin)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("FRONTEND_ORIGIN");
        }
    }

    @Test
    @DisplayName("신호가 여럿이면 한 번에 모두 알려 준다 — 값(토큰)은 문구에 담지 않는다")
    void allProblemsAreListedWithoutValues() {
        String token = DemoViewerTest.TOKEN;
        AppProperties aws = with(p -> {
            p.setDemoViewerToken(token);
            p.setCookieSecure(true);
            p.setFrontendOrigin("https://dxxxx.cloudfront.net");
        });
        assertThatThrownBy(() -> LocalNoLogin.check(aws, "native"))
                .hasMessageContaining("DASHBOARD_DEMO_VIEWER_TOKEN").hasMessageContaining("COOKIE_SECURE")
                .hasMessageContaining("forward-headers-strategy").hasMessageContaining("FRONTEND_ORIGIN")
                .hasMessageNotContaining(token).hasMessageNotContaining("cloudfront");
    }

    @Test
    @DisplayName("꺼져 있으면(기본·AWS·QA) 웹 배포 설정을 보지 않는다 — 지금까지처럼 기동")
    void offChecksNothing() {
        AppProperties aws = with(p -> {
            p.setLocalNoLogin(false);
            p.setDemoViewerToken(DemoViewerTest.TOKEN);
            p.setCookieSecure(true);
            p.setFrontendOrigin("https://dxxxx.cloudfront.net");
        });
        LocalNoLogin off = new LocalNoLogin(aws, "native");
        assertThat(off.enabled()).isFalse();
        assertThat(new AppProperties().isLocalNoLogin()).as("설정 기본값은 끔").isFalse();
    }

    @Test
    @DisplayName("앞단 헤더 전략은 스프링 부트와 같은 방식으로 읽는다 — AWS의 환경 변수 SERVER_FORWARDHEADERSSTRATEGY=native도 잡는다")
    void forwardHeadersStrategyIsReadLikeSpringBoot() {
        MockEnvironment aws = new MockEnvironment();
        aws.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("SERVER_FORWARDHEADERSSTRATEGY", "native")));
        assertThat(LocalNoLogin.forwardHeadersStrategy(aws)).isEqualTo("native");
        assertThatThrownBy(() -> new LocalNoLogin(local(), aws)).isInstanceOf(IllegalStateException.class);

        MockEnvironment property = new MockEnvironment().withProperty("server.forward-headers-strategy", "framework");
        assertThatThrownBy(() -> new LocalNoLogin(local(), property)).isInstanceOf(IllegalStateException.class);

        MockEnvironment unset = new MockEnvironment();
        assertThat(LocalNoLogin.forwardHeadersStrategy(unset)).isEqualTo("none");
        assertThat(new LocalNoLogin(local(), unset).enabled()).isTrue();
    }

    private static MockHttpServletRequest request(String serverName) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/macro/overview");
        request.setServerName(serverName);
        return request;
    }

    @Test
    @DisplayName("이 맥에서 바로 온 요청 = Host가 localhost·127.0.0.1·[::1]")
    void loopbackHostIsLocal() {
        for (String host : new String[]{"localhost", "LOCALHOST", "127.0.0.1", "::1", "[::1]"}) {
            assertThat(LocalNoLogin.isLocalRequest(request(host))).as(host).isTrue();
        }
        for (String host : new String[]{"192.168.0.10", "evil.example", "localhost.evil.example", "127.0.0.2",
                "backend", ""}) {
            assertThat(LocalNoLogin.isLocalRequest(request(host))).as(host).isFalse();
        }
    }

    @Test
    @DisplayName("앞단 프록시 헤더(X-Forwarded-For·Forwarded·X-Forwarded-Host)나 데모 열람 헤더가 있으면 이 맥 요청이 아니다 — 빈 값이어도")
    void proxyHeadersMakeItNonLocal() {
        for (String header : new String[]{"X-Forwarded-For", "Forwarded", "X-Forwarded-Host", DemoViewer.HEADER,
                "x-forwarded-for"}) {
            for (String value : new String[]{"127.0.0.1", ""}) {
                MockHttpServletRequest request = request("localhost");
                request.addHeader(header, value);
                assertThat(LocalNoLogin.isLocalRequest(request)).as(header + "=" + value).isFalse();
            }
        }
    }
}
