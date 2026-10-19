package com.macrodash.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로컬 무로그인(ADR 0016) 통합 테스트 — 실제 웹 서버·필터·컨트롤러·테스트 DB로, 쿠키 없이 이 맥(localhost)에서 부른
 * 요청이 첫 운영자로 열리는지 봅니다.
 *
 * <p>{@link ApiIntegrationTest}(데모 열람을 켬)와 설정이 달라 컨텍스트를 따로 띄웁니다 — 이 클래스는 데모 토큰을
 * 비우고, 앞단 헤더 전략·Secure 쿠키·화면 주소도 로컬 값으로 고정합니다(셸 환경이 섞여 기동 검사에 걸리지 않게).
 * 운영자가 없으면 이 테스트 전용 운영자를 만들고 끝나면 지웁니다. 다른 테스트의 계정은 건드리지 않습니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://localhost:5432/macrodash_test}",
        "dashboard.local-no-login=true",
        "dashboard.demo-viewer-token=",
        "dashboard.cookie-secure=false",
        "server.forward-headers-strategy=none",
        "dashboard.frontend-origin=http://localhost:3000",
        "dashboard.frontend-port=3000",
        // 첫 계정 만들기(빈 테이블일 때 OWNER_EMAIL로)가 셸 환경으로 테스트 DB에 계정을 만들지 않게 비웁니다.
        "dashboard.owner-email=", "dashboard.password=",
        "dashboard.read-mode=store_only",
        "dashboard.collector-url=http://localhost:1",
        "dashboard.ai.anthropic-api-key=", "dashboard.ai.nvidia-api-key=", "dashboard.ai.cerebras-api-key=",
        "dashboard.ai.cloudflare-account-id=", "dashboard.ai.cloudflare-api-token=",
        "dashboard.scheduler.enabled=false"
})
class LocalNoLoginIntegrationTest {

    /** 테스트 DB에 운영자가 없을 때만 만드는 이 테스트 전용 계정. */
    static final String OWN_OWNER = "nologin-it-owner@test.local";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.macrodash.store.UserStore users;

    private long ownerId;
    /** 이 테스트가 시작할 때의 알림 규칙 최대 번호 — 이보다 큰 이 테스트의 행만 지웁니다. */
    private long ruleMark;

    @BeforeEach
    void setUp() {
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        assertThat(database).as("테스트 전용 DB에서만 돕니다").endsWith("_test");
        if (users.firstOwner().isEmpty()) {
            users.insert(OWN_OWNER, "not-a-login-hash", "OWNER", "PRO", true);
        }
        ownerId = users.firstOwner().orElseThrow().id();
        ruleMark = jdbc.queryForObject("SELECT COALESCE(max(id), 0) FROM alert_rules", Long.class);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM alert_rules WHERE id > ? AND user_id = ?", ruleMark, ownerId);
        jdbc.update("DELETE FROM users WHERE email = ?", OWN_OWNER);
    }

    @Test
    @DisplayName("쿠키 없는 GET이 첫 운영자로 열린다 — 회원 경로·운영자 경로 모두 200, 세션 확인은 noLogin·user.local")
    void cookieLessReadsAreOwner() {
        assertThat(rest.getForEntity(url("/api/alerts/rules"), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> admin = rest.getForEntity(url("/api/admin/users"), JsonNode.class);
        assertThat(admin.getStatusCode()).as("운영자 경로").isEqualTo(HttpStatus.OK);

        JsonNode session = rest.getForEntity(url("/api/auth/session"), JsonNode.class).getBody();
        assertThat(session.path("authenticated").asBoolean()).isTrue();
        assertThat(session.path("noLogin").asBoolean()).isTrue();
        assertThat(session.path("ownerMissing").asBoolean(true)).isFalse();
        assertThat(session.path("user").path("local").asBoolean()).isTrue();
        assertThat(session.path("user").path("role").asString()).isEqualTo("OWNER");
        assertThat(session.path("user").path("email").asString())
                .isEqualTo(users.findById(ownerId).orElseThrow().email());
    }

    @Test
    @DisplayName("이 맥 화면의 Origin을 단 쓰기는 200이고 운영자 계정으로 저장된다")
    void writeWithLocalOriginIsStoredAsOwner() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.ORIGIN, "http://localhost:3000");
        ResponseEntity<String> created = rest.exchange(url("/api/alerts/rules"), HttpMethod.POST,
                new HttpEntity<>(Map.of("kind", "REGIME_CHANGE", "params", Map.of()), headers), String.class);

        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.OK);
        List<Long> owners = jdbc.queryForList("SELECT user_id FROM alert_rules WHERE id > ? AND kind = 'REGIME_CHANGE'",
                Long.class, ruleMark);
        assertThat(owners).containsExactly(ownerId);
    }

    @Test
    @DisplayName("다른 출처의 Origin·Origin 없는 쓰기는 403, 아무것도 저장하지 않는다")
    void foreignOrOriginLessWriteIsRejected() {
        for (String origin : new String[]{"https://evil.example", "http://192.168.0.10:3000", null}) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (origin != null) {
                headers.add(HttpHeaders.ORIGIN, origin);
            }
            ResponseEntity<JsonNode> response = rest.exchange(url("/api/alerts/rules"), HttpMethod.POST,
                    new HttpEntity<>(Map.of("kind", "REGIME_CHANGE", "params", Map.of()), headers), JsonNode.class);
            assertThat(response.getStatusCode()).as(String.valueOf(origin)).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody().path("error").asString()).isEqualTo("bad_origin");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alert_rules WHERE id > ?", Long.class, ruleMark)).isZero();
    }

    @Test
    @DisplayName("앞단 프록시 헤더가 붙은 요청은 이 맥 요청이 아니다 — 쿠키 없으면 401")
    void forwardedRequestNeedsLogin() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "203.0.113.5");
        assertThat(rest.exchange(url("/api/alerts/rules"), HttpMethod.GET, new HttpEntity<>(null, headers), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
