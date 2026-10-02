package com.macrodash.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
