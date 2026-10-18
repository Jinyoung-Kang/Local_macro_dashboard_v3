package com.macrodash.web;

import com.macrodash.store.Datasets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.filter.FormContentFilter;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API 통합 테스트 (실제 PostgreSQL 필요).
 *
 * <p>{@code TEST_DATABASE_URL}이 없으면 Spring이 데이터소스를 만들지 못해
 * 컨텍스트 기동이 실패하므로, CI에서는 서비스 컨테이너로 DB를 띄웁니다.
 * 로컬에서 DB 없이 돌릴 때는 {@code -Dtest=!ApiIntegrationTest}로 제외하세요.
 *
 * <p>확인하는 것
 * <ul>
 *   <li>인증 없이는 데이터 API에 접근할 수 없다</li>
 *   <li>로그인 후에는 저장본을 읽어 화면 계약대로 응답한다</li>
 *   <li>저장본이 없어도 500이 아니라 "available=false"로 답한다</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://localhost:5432/macrodash_test}",
        "dashboard.password=test-password",
        "dashboard.owner-email=" + ApiIntegrationTest.OWNER,
        "dashboard.read-mode=store_only",
        "dashboard.collector-url=http://localhost:1",    // 수집기가 없어도 화면은 떠야 합니다
        "dashboard.ai.timeout-seconds=333",              // 설정 주입이 실제로 먹는지 확인용
        // AI 키는 셸 환경과 상관없이 비웁니다 — 데모의 AI 리포트 요청이 외부(유료) 호출 없이 "키 없음"으로 끝나게.
        "dashboard.ai.anthropic-api-key=", "dashboard.ai.nvidia-api-key=", "dashboard.ai.cerebras-api-key=",
        "dashboard.ai.cloudflare-account-id=", "dashboard.ai.cloudflare-api-token=",
        "dashboard.ai.demo-daily-limit=7",
        "dashboard.scheduler.enabled=false",             // 정기 작업이 테스트 DB에 쓰면 결과가 실행 시각에 따라 달라짐
        "dashboard.scheduler.regime-record-hour-kst=17", // 설정 주입이 실제로 먹는지 확인용(QA-093, AWS 배포값)
        // 데모 열람(ADR 0013)을 켜 둡니다 — 헤더를 보내지 않는 다른 테스트는 평소와 같습니다(그것도 함께 확인됨).
        "dashboard.demo-viewer-token=" + ApiIntegrationTest.DEMO_TOKEN
})
class ApiIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    com.macrodash.feature.ai.AiService ai;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.macrodash.store.StoreRepository repository;

    @Autowired
    com.macrodash.store.UserStore users;

    @Autowired
    com.macrodash.feature.auth.PasswordHasher hasher;

    @Autowired
    ApplicationContext context;

    static final String OWNER = "owner@test.local";
    static final String MEMBER = "member@test.local";
    /** 테스트용 데모 열람 토큰(32자 이상). 실제 배포 토큰은 서버의 .env에만 있습니다. */
    static final String DEMO_TOKEN = "test-demo-viewer-token-0123456789abcdef";

    private String sessionCookie;

    @BeforeEach
    void setUp() {
        guardAgainstRealDatabase();
        jdbc.execute("DELETE FROM snapshots");
        jdbc.update("DELETE FROM observations WHERE dataset = ?", Datasets.OBS_TOSS_STOCK_FLOW);
        // 테스트 DB에는 다른 테스트가 만든 계정이 남아 있을 수 있어, 첫 계정 만들기(빈 테이블일 때만)에 기대지 않습니다.
        ensureUser(OWNER, "OWNER");
        ensureUser(MEMBER, "MEMBER");
        sessionCookie = login(OWNER, "test-password");
    }

    private void ensureUser(String email, String role) {
        if (users.findByEmail(email).isEmpty()) {
            users.insert(email, hasher.hash("test-password"), role, "FREE", true);
        }
    }

    /**
     * 운영 DB에 붙었으면 <b>지우기 전에</b> 멈춥니다.
     *
     * <p>이 테스트는 시작할 때마다 snapshots를 비웁니다. 접속 대상이 실제로
     * 쓰는 DB(macrodash)였을 때는 테스트 한 번에 수집해 둔 데이터가 전부
     * 사라졌습니다 — 되살리려면 전체 재수집(10분 이상)이 필요합니다.
     * 이름이 {@code _test}로 끝나는 DB에서만 돌게 막아 둡니다.
     */
    private void guardAgainstRealDatabase() {
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        if (database == null || !database.endsWith("_test")) {
            throw new IllegalStateException(
                    "이 테스트는 데이터를 지우므로 테스트 전용 DB에서만 돌릴 수 있습니다. "
                            + "지금 접속한 DB: '" + database + "'. "
                            + "'make test-backend'를 쓰거나 TEST_DATABASE_URL을 "
                            + "…/macrodash_test 로 지정하세요.");
        }
    }

    @Test
    @DisplayName("인증 없이는 데이터 API에 접근할 수 없다")
    void requiresAuthentication() {
        ResponseEntity<String> response = rest.getForEntity(
                url("/api/macro/overview"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("QA-069: 인증 전에 PUT·PATCH·DELETE의 form 본문을 상한 없이 읽는 Spring 기본 필터(FormContentFilter)가 없다")
    void noFormContentFilterBeforeAuthentication() {
        // 이 필터는 SessionFilter(order 1)보다 앞(order -9900, 모든 경로)에서 돌아, 로그인 없이 보낸 수백 MB form 본문을
        // 통째로 문자열로 만듭니다 — 본문 상한(SessionFilter.capBody)보다 먼저라 막지 못합니다. 요청으로 힙을 실제로
        // 고갈시키는 시험은 같은 JVM의 다른 테스트를 흔들므로, 필터가 등록되지 않았는지만 봅니다.
        assertThat(context.getBeansOfType(FormContentFilter.class)).isEmpty();
    }

    @Test
    @DisplayName("로그아웃한 세션 쿠키는 만료 전이라도 다시 쓸 수 없다 (SEC-07)")
    void loggedOutSessionCannotBeReused() {
        // 예전에는 로그아웃이 브라우저 쿠키만 지워서, 쿠키 값을 가진 쪽은 12시간 동안 계속 쓸 수 있었습니다.
        String stolen = login(OWNER, "test-password");
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, stolen);
        assertThat(rest.exchange(url("/api/macro/overview"), HttpMethod.GET,
                new HttpEntity<>(null, headers), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        rest.exchange(url("/api/auth/logout"), HttpMethod.POST, new HttpEntity<>(null, headers), String.class);

        assertThat(rest.exchange(url("/api/macro/overview"), HttpMethod.GET,
                new HttpEntity<>(null, headers), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        JsonNode session = rest.exchange(url("/api/auth/session"), HttpMethod.GET,
                new HttpEntity<>(null, headers), JsonNode.class).getBody();
        assertThat(session.path("authenticated").asBoolean(true)).isFalse();
        // 다른 세션(이 테스트의 기본 로그인)은 그대로입니다.
        assertThat(authorizedGet("/api/auth/session").path("authenticated").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("일반 회원은 데이터는 보지만 운영 경로(수집 실행·상태)는 403 — 막는 것은 백엔드다(ADR 0010)")
    void memberCannotCallOwnerRoutes() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, login(MEMBER, "test-password"));
        assertThat(rest.exchange(url("/api/analytics/regime/log"), HttpMethod.GET,
                new HttpEntity<>(null, headers), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> refresh = rest.exchange(url("/api/status/refresh"), HttpMethod.POST,
                new HttpEntity<>(null, headers), JsonNode.class);
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refresh.getBody().path("required").asText()).isEqualTo("OWNER");
        assertThat(rest.exchange(url("/api/status"), HttpMethod.GET,
                new HttpEntity<>(null, headers), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        JsonNode session = rest.exchange(url("/api/auth/session"), HttpMethod.GET,
                new HttpEntity<>(null, headers), JsonNode.class).getBody();
        assertThat(session.path("user").path("role").asText()).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("QA-093: 판정 기록 시각은 설정값을 따른다 — AWS 데모(평일 08:50–18:00 운영)는 18시 전에 기록해야 쌓인다")
    void regimeRecordHourFollowsConfiguration() {
        JsonNode log = authorizedGet("/api/analytics/regime/log");

        assertThat(log.path("available").asBoolean()).isTrue();
        assertThat(log.path("recordHourKst").asInt()).isEqualTo(17);
        assertThat(log.path("note").asString()).contains("KST 17시 이후");
    }

    @Test
    @DisplayName("HEAD도 GET과 같은 접근 검사 — 무료 회원은 운영·유료 경로가 403, 회원 경로는 200(QA-017)")
    void headRequestsFollowGetAccessPolicy() {
        long memberId = users.findByEmail(MEMBER).orElseThrow().id();
        users.updateRoleAndTier(memberId, "MEMBER", "FREE");
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, login(MEMBER, "test-password"));
        for (String path : List.of("/api/status", "/api/admin/users", "/api/kr/stock-flows?codes=005930")) {
            assertThat(rest.exchange(url(path), HttpMethod.HEAD, new HttpEntity<>(null, headers), String.class)
                    .getStatusCode()).as("HEAD " + path).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(rest.exchange(url("/api/analytics/regime/log"), HttpMethod.HEAD,
                new HttpEntity<>(null, headers), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("QA-091 운영자가 유료 → 무료로 내리면 국면 전환 밖의 알림 규칙이 꺼지고(감사 기록에 개수), 회원이 다시 켤 수 없다")
    void tierDowngradeDisablesPaidAlertRules() {
        long memberId = users.findByEmail(MEMBER).orElseThrow().id();
        users.updateRoleAndTier(memberId, "MEMBER", "PRO");
        jdbc.update("DELETE FROM alert_rules WHERE user_id = ?", memberId);
        try {
            for (String[] rule : new String[][]{
                    {"REGIME_CHANGE", "{}"},
                    {"FOREIGN_STREAK", "{\"target\":\"KOSPI\",\"n\":5}"},
                    {"FOREIGN_STREAK", "{\"target\":\"STOCK\",\"code\":\"005930\",\"n\":5}"},
                    {"INSTITUTION_NEW", "{\"cik\":\"0001067983\"}"}}) {
                jdbc.update("INSERT INTO alert_rules (user_id, kind, params) VALUES (?, ?, ?::jsonb)",
                        memberId, rule[0], rule[1]);
            }
            HttpHeaders owner = new HttpHeaders();
            owner.add(HttpHeaders.COOKIE, sessionCookie);
            owner.setContentType(MediaType.APPLICATION_JSON);
            assertThat(rest.exchange(url("/api/admin/users/" + memberId), HttpMethod.POST,
                    new HttpEntity<>(Map.of("role", "MEMBER", "tier", "FREE"), owner), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);

            // 수집기(관심 종목 범위)는 enabled만 보므로 꺼야 따라옵니다(정기 평가는 등급으로도 거름 — QA-115).
            List<Map<String, Object>> rules = jdbc.queryForList(
                    "SELECT id, kind, enabled FROM alert_rules WHERE user_id = ? ORDER BY id", memberId);
            assertThat(rules).extracting(row -> row.get("kind") + ":" + row.get("enabled")).containsExactly(
                    "REGIME_CHANGE:true", "FOREIGN_STREAK:false", "FOREIGN_STREAK:false", "INSTITUTION_NEW:false");
            assertThat(jdbc.queryForObject("SELECT detail->>'disabledAlertRules' FROM audit_log "
                    + "WHERE action = 'user.updated' AND detail->>'target' = ? ORDER BY id DESC LIMIT 1",
                    String.class, String.valueOf(memberId))).as("감사 기록에 끈 규칙 수").isEqualTo("3");

            HttpHeaders member = new HttpHeaders();
            member.add(HttpHeaders.COOKIE, login(MEMBER, "test-password"));
            member.setContentType(MediaType.APPLICATION_JSON);
            assertThat(rest.exchange(url("/api/alerts/rules/" + rules.get(1).get("id")), HttpMethod.POST,
                    new HttpEntity<>(Map.of("enabled", true), member), String.class).getStatusCode())
                    .as("무료 등급은 유료 종류 규칙을 다시 켤 수 없음").isEqualTo(HttpStatus.FORBIDDEN);
        } finally {
            jdbc.update("DELETE FROM alert_rules WHERE user_id = ?", memberId);
            users.updateRoleAndTier(memberId, "MEMBER", "FREE");
        }
    }

    @Test
    @DisplayName("무료 회원은 유료 경로가 403 required=PRO, 운영자가 등급을 바꾸면 다음 요청부터 열린다")
    void tierChangeAppliesOnNextRequest() {
        long memberId = users.findByEmail(MEMBER).orElseThrow().id();
        users.updateRoleAndTier(memberId, "MEMBER", "FREE");
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, login(MEMBER, "test-password"));

        ResponseEntity<JsonNode> locked = rest.exchange(url("/api/kr/stock-flows?codes=005930"), HttpMethod.GET,
                new HttpEntity<>(null, headers), JsonNode.class);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(locked.getBody().path("required").asText()).isEqualTo("PRO");

        HttpHeaders owner = new HttpHeaders();
        owner.add(HttpHeaders.COOKIE, sessionCookie);
        owner.setContentType(MediaType.APPLICATION_JSON);
        assertThat(rest.exchange(url("/api/admin/users/" + memberId), HttpMethod.POST,
                new HttpEntity<>(Map.of("role", "MEMBER", "tier", "PRO"), owner), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange(url("/api/kr/stock-flows?codes=005930"), HttpMethod.GET,
                new HttpEntity<>(null, headers), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        users.updateRoleAndTier(memberId, "MEMBER", "FREE");
    }

    @Test
    @DisplayName("없는 이메일과 틀린 비밀번호는 같은 응답이다 — 계정 존재를 드러내지 않는다")
    void unknownEmailLooksLikeWrongPassword() {
        ResponseEntity<String> unknown = rest.postForEntity(url("/api/auth/login"),
                Map.of("email", "nobody@test.local", "password", "test-password"), String.class);
        ResponseEntity<String> wrong = rest.postForEntity(url("/api/auth/login"),
                Map.of("email", MEMBER, "password", "not-the-password"), String.class);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknown.getBody()).isEqualTo(wrong.getBody());
    }

    @Test
    @DisplayName("메일 발송이 설정되지 않으면 가입·재설정은 503, 로그인 화면은 그 사실을 세션 응답으로 안다")
    void signupNeedsMail() {
        ResponseEntity<String> signup = rest.postForEntity(url("/api/auth/signup"),
                Map.of("email", "new@test.local", "password", "a long enough passphrase", "agreed", true), String.class);
        assertThat(signup.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        JsonNode session = rest.getForEntity(url("/api/auth/session"), JsonNode.class).getBody();
        assertThat(session.path("mailAvailable").asBoolean(true)).isFalse();
    }

    @Test
    @DisplayName("다른 출처에서 온 상태 변경 요청은 403 (Origin 확인)")
    void foreignOriginPostIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie);
        headers.add(HttpHeaders.ORIGIN, "https://evil.example");
        assertThat(rest.exchange(url("/api/auth/logout"), HttpMethod.POST, new HttpEntity<>(null, headers),
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("잘못된 비밀번호는 거부된다")
    void rejectsWrongPassword() {
        ResponseEntity<String> response = rest.postForEntity(
                url("/api/auth/login"), Map.of("email", OWNER, "password", "wrong"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("저장본이 없어도 500이 아니라 available=false로 답한다")
    void emptyStoreDoesNotBreakScreens() {
        for (String path : List.of(
                "/api/macro/overview", "/api/liquidity", "/api/sector/rotation",
                "/api/krx/futures", "/api/cot/overview",
                "/api/kr/investor-flows", "/api/krx/spot-futures", "/api/kr/stock-flows?codes=005930")) {
            JsonNode body = authorizedGet(path);
            assertThat(body.path("available").asBoolean(true))
                    .as("%s는 저장본이 없을 때 available=false여야 합니다", path)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("종목 수급: 요청한 종목의 최근 기록만 날짜 역순으로 읽어 요약한다")
    void stockFlowsReadsRecentSeriesOnly() {
        java.time.LocalDate today = com.macrodash.Kst.today();
        insertFlow("005930", today, 300);
        insertFlow("005930", today.minusDays(1), -100);
        insertFlow("005930", today.minusDays(60), 99999);   // 조회 범위(45일) 밖
        insertFlow("000660", today, 5);                     // 요청하지 않은 종목

        JsonNode body = authorizedGet("/api/kr/stock-flows?codes=005930,035420,bad");
        JsonNode stocks = body.path("stocks");

        assertThat(stocks).hasSize(2);   // 형식이 틀린 코드는 무시
        JsonNode samsung = stocks.get(0);
        assertThat(samsung.path("available").asBoolean()).isTrue();
        assertThat(samsung.path("records").asInt()).isEqualTo(2);
        assertThat(samsung.path("latestDate").asString()).isEqualTo(today.toString());
        JsonNode foreigner = samsung.path("investors").get(0);
        assertThat(foreigner.path("net5").path("sum").asLong()).isEqualTo(200);
        assertThat(foreigner.path("streak").asInt()).isEqualTo(1);
        assertThat(stocks.get(1).path("available").asBoolean(true)).isFalse();
    }

    private void insertFlow(String code, java.time.LocalDate date, long foreignerNet) {
        String payload = """
                {"code":"%s","date":"%s","updatedAt":"%sT18:10:00+09:00",
                 "investors":{"foreigner":{"buy":null,"sell":null,"net":%d},"institution":null,
                              "individual":null,"otherCorporation":null},
                 "breakdown":null,"foreignerHoldingRate":null}
                """.formatted(code, date, date, foreignerNet);
        jdbc.update("INSERT INTO observations (dataset, obs_date, entity, payload) VALUES (?, ?, ?, ?::jsonb)",
                Datasets.OBS_TOSS_STOCK_FLOW, java.sql.Date.valueOf(date), code, payload);
    }

    @Test
    @DisplayName("매크로 저장본을 그대로 화면 계약으로 전달한다")
    void servesStoredMacroSnapshot() {
        insertSnapshot(Datasets.SNAP_MACRO_COLLECTED, """
                {
                  "categories": [{
                    "id": "fx", "title": "💵 통화 및 환율", "note": "실시간",
                    "items": [{
                      "key": "usdkrw", "name": "원/달러 (USD/KRW)", "status": "ok",
                      "price": 1389.5, "priceStr": "1,389.50",
                      "delta": -3.2, "pct": -0.23, "deltaStr": "-3.20 (-0.23%)",
                      "prevStr": "1,392.70", "prevValue": 1392.7,
                      "lastTs": "15:30:00 KST"
                    }]
                  }],
                  "rates": {
                    "us02y": {"current": 3.62, "previous": 3.58},
                    "us10y": {"current": 4.11, "previous": 4.05}
                  }
                }
                """);

        JsonNode body = authorizedGet("/api/macro/overview");

        assertThat(body.path("available").asBoolean()).isTrue();
        assertThat(body.path("categories").get(0).path("items").get(0).path("priceStr").asString())
                .isEqualTo("1,389.50");
        // 10Y − 2Y = 4.11 − 3.62 = 0.49
        assertThat(body.path("spreads").path("realtime").path("spread").asDouble())
                .isEqualTo(0.49, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @DisplayName("추정치 KRX 저장본은 isEstimated로 표시된다")
    void estimatedKrxSnapshotIsFlagged() {
        insertSnapshot(Datasets.SNAP_KRX_FUTURES, """
                {
                  "isEstimated": true,
                  "rows": [{
                    "date": "2026-09-11", "futuresClose": 1088.3, "changePct": -2.13,
                    "changePctReported": null, "volume": null, "openInterest": null,
                    "oiChange": null, "theoryPrice": null, "marketBasis": null,
                    "contractName": "KOSPI 200 최근월물 (KODEX 200 기반 추정)",
                    "marketPhase": "판정 불가 (등락률 미제공)", "cotOiIndex": null
                  }]
                }
                """, "estimated");

        JsonNode body = authorizedGet("/api/krx/futures");

        assertThat(body.path("isEstimated").asBoolean()).isTrue();
        assertThat(body.path("estimateNotice").asString()).contains("추정치");
        assertThat(body.path("latest").path("marketPhase").asString()).isEqualTo("판정 불가 (등락률 미제공)");
        // 베이시스를 모르면 0으로 메우지 않고 "데이터 미제공"으로 표시합니다.
        assertThat(body.path("latest").path("basisState").asString()).isEqualTo("데이터 미제공");
    }

    @Test
    @DisplayName("store_only 모드에서는 수집기가 죽어 있어도 응답한다")
    void storeOnlyModeNeverWaitsForCollector() {
        JsonNode body = authorizedGet("/api/status");

        assertThat(body.path("readMode").asString()).isEqualTo("store_only");
        assertThat(body.path("collectorReachable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("로그아웃하면 세션이 무효가 된다")
    void logoutClearsSession() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie);

        rest.exchange(url("/api/auth/logout"), HttpMethod.POST,
                new HttpEntity<>(null, headers), String.class);

        ResponseEntity<String> after = rest.exchange(
                url("/api/auth/session"), HttpMethod.GET,
                new HttpEntity<>(null, headers), String.class);

        assertThat(after.getBody()).contains("authenticated");
    }

    /** 사용자 번호를 키로 쓰는 테이블(모두 users FK) — 데모 열람이 여기에 한 줄이라도 쓰면 안 됩니다. */
    private static final List<String> USER_KEYED_TABLES = List.of(
            "user_sessions", "audit_log", "usage_counters", "alert_rules", "alert_deliveries", "email_tokens");

    private Map<String, Long> userKeyedRowCounts() {
        Map<String, Long> counts = new java.util.TreeMap<>();
        for (String table : USER_KEYED_TABLES) {
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
        return counts;
    }

    @Test
    @DisplayName("데모 열람(ADR 0013): 로그인 없이 화면 GET이 200(사용자별 테이블을 읽는 경로 포함), AI 리포트 요청까지 DB에 아무것도 쓰지 않는다")
    void demoViewerReadsWithoutWriting() {
        HttpHeaders demo = new HttpHeaders();
        demo.add("X-Demo-Viewer", DEMO_TOKEN);
        Map<String, Long> before = userKeyedRowCounts();

        JsonNode session = rest.exchange(url("/api/auth/session"), HttpMethod.GET, new HttpEntity<>(null, demo),
                JsonNode.class).getBody();
        assertThat(session.path("authenticated").asBoolean()).isTrue();
        assertThat(session.path("user").path("demo").asBoolean()).isTrue();
        assertThat(session.path("user").path("email").isNull()).isTrue();

        for (String path : List.of("/api/auth/sessions", "/api/alerts/rules", "/api/alerts/inbox", "/api/alerts/unread-count",
                "/api/analytics/regime/log", "/api/analytics/regime/track-record", "/api/kr/stock-flows?codes=005930",
                "/api/macro/overview", "/api/ai/engines", "/api/ai/report-types")) {
            ResponseEntity<String> response = rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(null, demo),
                    String.class);
            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        }
        JsonNode inbox = rest.exchange(url("/api/alerts/inbox"), HttpMethod.GET, new HttpEntity<>(null, demo),
                JsonNode.class).getBody();
        assertThat(inbox.path("items").size()).as("데모의 알림함은 비어 있음").isZero();

        assertThat(rest.exchange(url("/api/status"), HttpMethod.GET, new HttpEntity<>(null, demo), String.class)
                .getStatusCode()).as("운영 경로").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.exchange(url("/api/status"), HttpMethod.HEAD, new HttpEntity<>(null, demo), String.class)
                .getStatusCode()).as("HEAD로 운영 경로를 돌리지 못함").isEqualTo(HttpStatus.FORBIDDEN);

        HttpHeaders demoJson = new HttpHeaders();
        demoJson.addAll(demo);
        demoJson.setContentType(MediaType.APPLICATION_JSON);
        for (String path : List.of("/api/alerts/rules", "/api/alerts/inbox/read", "/api/ai/test", "/api/auth/logout")) {
            ResponseEntity<JsonNode> write = rest.exchange(url(path), HttpMethod.POST,
                    new HttpEntity<>(Map.of("kind", "REGIME_CHANGE"), demoJson), JsonNode.class);
            assertThat(write.getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(write.getBody().path("error").asString()).isEqualTo("demo_read_only");
        }

        // AI 리포트는 데모의 유일한 쓰기(ADR 0014) — 공용 하루 한도(메모리)로 세고 DB에는 쓰지 않습니다.
        // 이 테스트에는 AI 키가 없어 엔진 호출이 "키 없음"으로 끝나고(외부 호출 없음), 한도는 되돌려집니다.
        JsonNode engines = rest.exchange(url("/api/ai/engines"), HttpMethod.GET, new HttpEntity<>(null, demo),
                JsonNode.class).getBody();
        assertThat(engines.path("quota").path("limit").asInt()).isEqualTo(7);
        assertThat(engines.path("quota").path("shared").asBoolean()).isTrue();
        ResponseEntity<JsonNode> report = rest.exchange(url("/api/ai/report"), HttpMethod.POST,
                new HttpEntity<>(Map.of("engineId", "anthropic_haiku"), demoJson), JsonNode.class);
        assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(report.getBody().path("status").asBoolean()).isFalse();
        assertThat(report.getBody().path("quota").path("used").asInt()).as("실패는 되돌림").isZero();
        assertThat(report.getBody().path("quota").path("limit").asInt()).isEqualTo(7);
        assertThat(report.getBody().path("quota").path("shared").asBoolean()).isTrue();

        assertThat(userKeyedRowCounts()).as("사용자별 테이블의 행 수가 그대로").isEqualTo(before);
        for (String table : USER_KEYED_TABLES) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id < 1", Long.class))
                    .as(table).isZero();
        }
    }

    @Test
    @DisplayName("데모 열람 헤더가 틀리면 401 — 헤더만으로는 열리지 않는다")
    void wrongDemoHeaderIsUnauthorized() {
        HttpHeaders wrong = new HttpHeaders();
        wrong.add("X-Demo-Viewer", "not-the-token");
        assertThat(rest.exchange(url("/api/macro/overview"), HttpMethod.GET, new HttpEntity<>(null, wrong), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------ helpers
    private String login(String email, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = rest.postForEntity(
                url("/api/auth/login"),
                new HttpEntity<>(Map.of("email", email, "password", password), headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull().isNotEmpty();
        return cookies.get(0).split(";")[0];
    }

    @Test
    @DisplayName("13F: quarters가 0이나 음수여도 500이 아니다")
    void sec13fSurvivesOutOfRangeQuarters() {
        // quarters는 URL 파라미터입니다. 예전에는 그대로 subList(0, quarters)에
        // 넘겨서 quarters=0이면 빈 목록의 get(0)으로, quarters=-1이면 subList가
        // 곧바로 예외를 던져 500이 났습니다.
        String cik = "0001067983";      // 버크셔
        insertSnapshot(
                Datasets.sec13f(cik, Datasets.MAX_TRACKED_QUARTERS),
                """
                {"quarters":[
                  {"filingDate":"2026-08-14","reportDate":"2026-06-30","totalValue":1000,
                   "holdings":[{"name":"APPLE INC","value":600,"weight":60.0},
                               {"name":"COCA COLA CO","value":400,"weight":40.0}]},
                  {"filingDate":"2026-05-15","reportDate":"2026-03-31","totalValue":900,
                   "holdings":[{"name":"APPLE INC","value":500,"weight":55.6},
                               {"name":"COCA COLA CO","value":400,"weight":44.4}]}
                ]}
                """);

        for (int quarters : new int[]{0, -1, 1, 8, 999}) {
            JsonNode body = authorizedGet(
                    "/api/sec13f/portfolio?cik=" + cik + "&quarters=" + quarters);
            assertThat(body.path("available").asBoolean())
                    .as("quarters=%d 에서도 응답이 나와야 합니다", quarters)
                    .isTrue();
            assertThat(body.path("quarters").size())
                    .as("quarters=%d 에서 최소 한 분기는 나와야 합니다", quarters)
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("원본 데이터 텍스트는 저장본이 없어도 모든 영역을 표기한다")
    void snapshotTextAlwaysCoversEverySection() {
        // 📋 "전체 대시보드 원본 데이터 보기/복사"가 읽는 경로입니다.
        // 수집본이 하나도 없어도 500이 아니라, 어느 영역이 비었는지 보여 줘야
        // 합니다 — 빈 화면은 "이 메뉴가 고장났다"로 읽힙니다.
        JsonNode body = authorizedGet("/api/snapshot/text");

        String text = body.path("text").asString();
        assertThat(body.path("generatedAtKst").asString()).endsWith("KST");
        assertThat(body.path("chars").asInt()).isEqualTo(text.length());

        for (String section : List.of(
                "거시경제 매크로 지표", "심화 매크로 지표", "금융 리스크",
                "연준 순유동성", "섹터 & 자산군", "글로벌 투기세력",
                "국내 파생", "국내 수급 레이더", "기관 13F 스마트머니 교집합")) {
            assertThat(text).as("%s 영역이 텍스트에 있어야 합니다", section).contains(section);
        }
        // 값의 성격(확정치/스크래핑/추정치)을 텍스트 자체가 들고 다녀야 합니다.
        assertThat(text).contains("데이터 성격:");
    }

    @Test
    @DisplayName("원본 데이터 텍스트에는 수집 시각과 값의 성격이 함께 붙는다")
    void snapshotTextCarriesCollectionTimeAndNature() {
        insertSnapshot(Datasets.SNAP_MACRO_COLLECTED, """
                {
                  "categories": [{
                    "id": "fx", "title": "💵 통화 및 환율", "note": "실시간",
                    "items": [{
                      "key": "usdkrw", "name": "원/달러 (USD/KRW)", "status": "ok",
                      "price": 1389.5, "priceStr": "1,389.50",
                      "delta": -3.2, "pct": -0.23, "deltaStr": "-3.20 (-0.23%)",
                      "prevStr": "1,392.70", "prevValue": 1392.7,
                      "lastTs": "15:30:00 KST"
                    }]
                  }]
                }
                """);

        String text = authorizedGet("/api/snapshot/text").path("text").asString();

        assertThat(text).contains("- 수집 시각: ");
        assertThat(text).contains("원/달러 (USD/KRW): 1,389.50");
    }

    @Test
    @DisplayName("원본 데이터 텍스트는 응답 캐시를 거친다 — 같은 저장본이면 다시 만들지 않음")
    void snapshotTextIsServedFromTheResponseCache() {
        insertSnapshot(Datasets.SNAP_MACRO_COLLECTED, """
                {"categories": [{"id": "fx", "title": "💵 통화 및 환율", "items": [{
                  "key": "usdkrw", "name": "원/달러 (USD/KRW)", "status": "ok", "price": 1389.5, "priceStr": "1,389.50",
                  "deltaStr": "-3.20 (-0.23%)", "prevStr": "1,392.70", "lastTs": "15:30:00 KST"}]}]}
                """);
        String cached = authorizedGet("/api/snapshot/text").path("text").asString();
        assertThat(cached).contains("1,389.50");
        // 수집 시각(= 캐시의 저장본 버전)은 그대로 두고 값만 바꿉니다. 같은 캐시를 거치면 다시 만들지 않으므로 앞의 텍스트가
        // 그대로 나오고, 캐시 없이 매번 만들면 바뀐 값이 나옵니다. 예전에 이 텍스트를 내던 쌍둥이 경로
        // /api/ai/snapshot-text(QA-097)는 지웠고, 남은 이 경로의 캐시를 지킵니다.
        jdbc.update("UPDATE snapshots SET payload = replace(payload::text, '1,389.50', '1,400.00')::jsonb WHERE name = ?",
                Datasets.SNAP_MACRO_COLLECTED);

        assertThat(authorizedGet("/api/snapshot/text").path("text").asString()).isEqualTo(cached);
    }

    @Test
    @DisplayName("잘못된 파라미터는 400이고, 오류 본문에 내부 정보(예외·SQL·자바 타입)가 없다")
    void invalidParametersAreBadRequestsWithoutInternals() {
        // 예전에는 날짜 형식 오류가 500과 함께 "Text 'abc' could not be parsed…"를,
        // 숫자 형식 오류가 "Failed to convert value of type 'java.lang.String'…"을 돌려줬습니다.
        for (String path : List.of(
                "/api/radar/history?obsDate=abc",
                "/api/radar/history?startDate=2026-13-45",
                "/api/radar/ranking?topN=abc",
                "/api/macro/fred/DGS10?years=abc")) {
            ResponseEntity<JsonNode> response = authorizedExchange(path, HttpMethod.GET);
            assertThat(response.getStatusCode()).as("%s 상태", path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().path("error").asString()).as(path).isEqualTo("bad_request");
            assertThat(response.getBody().path("message").asString()).as(path).isNotBlank();
            assertThat(response.getBody().toString()).as(path)
                    .doesNotContain("java.", "Exception", "SQL", "could not be parsed", "Failed to convert");
        }
    }

    @Test
    @DisplayName("범위를 벗어난 숫자 파라미터는 500이 아니라 허용 범위로 접어 응답한다")
    void extremeNumbersAreClampedNot500() {
        // 예전: limit=-1 → SQL 오류 문장이 그대로 담긴 500, years=2147483647 → DateTimeException 500
        for (String path : List.of(
                "/api/status/history?limit=-1",
                "/api/status/history?limit=2147483647",
                "/api/macro/fred/DGS10?years=2147483647",
                "/api/liquidity?years=2147483647",
                "/api/analytics/regime?years=2147483647",
                "/api/analytics/correlation?x=fred:DGS10&y=fred:DGS2&years=2147483647")) {
            ResponseEntity<JsonNode> response = authorizedExchange(path, HttpMethod.GET);
            assertThat(response.getStatusCode()).as("%s 상태", path).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("추적하지 않는 CIK·모르는 FRED ID는 수집을 기다리지 않고 바로 400")
    void unknownIdentifiersAreRejectedUpFront() {
        // 예전에는 저장본이 "없음" → 전체 수집을 동기로 기다렸습니다(CIK 3개 = 수집 6회·30초 재현).
        for (String path : List.of(
                "/api/sec13f/portfolio?cik=0000000000",
                "/api/sec13f/consensus?ciks=1,2,3",
                "/api/sec13f/new-buys?ciks=0001067983,999",
                "/api/guru/risk?cik=0000000000",
                "/api/macro/fred/NOPE",
                "/api/macro/spread?longId=NOPE&shortId=DGS2")) {
            long started = System.nanoTime();
            ResponseEntity<JsonNode> response = authorizedExchange(path, HttpMethod.GET);
            long millis = (System.nanoTime() - started) / 1_000_000;

            assertThat(response.getStatusCode()).as("%s 상태", path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().path("error").asString()).as(path).isEqualTo("bad_request");
            assertThat(millis).as("%s 응답 시간(ms)", path).isLessThan(2_000);
        }
        // 파생 시리즈(30Y-3M)와 목록에 있는 ID는 그대로 통과합니다.
        assertThat(authorizedExchange("/api/macro/fred/T30Y3M", HttpMethod.GET).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(authorizedExchange("/api/macro/spread?longId=DGS10&shortId=DGS2", HttpMethod.GET)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("수급 이력 latest=true: 그 조건의 거래일 목록과 하루치만 준다 (전 기간을 보내지 않음)")
    void radarHistoryLatestReturnsOneDayOnly() {
        // 예전에는 전 기간 이력을 모두 보내고 화면이 30행만 썼습니다(1년치 합성 데이터: 7,800행·2.3MB).
        jdbc.update("DELETE FROM observations WHERE dataset = ?", Datasets.OBS_RADAR);
        insertRadar("2026-09-23", "외국인", "005930", 10.0);
        insertRadar("2026-09-24", "외국인", "005930", 20.0);
        insertRadar("2026-09-24", "외국인", "000660", 15.0);
        insertRadar("2026-09-25", "기관", "005930", 30.0);     // 다른 조건만 있는 날

        String base = "/api/radar/history?market=KOSPI&investor=외국인&tradeType=순매수";
        JsonNode latest = authorizedGet(base + "&latest=true");
        assertThat(latest.path("obsDate").asString()).isEqualTo("2026-09-24");
        assertThat(latest.path("dates").toString()).isEqualTo("[\"2026-09-24\",\"2026-09-23\"]");
        assertThat(latest.path("rows")).hasSize(2);
        latest.path("rows").forEach(row -> assertThat(row.path("obsDate").asString()).isEqualTo("2026-09-24"));

        JsonNode chosen = authorizedGet(base + "&latest=true&obsDate=2026-09-23");
        assertThat(chosen.path("obsDate").asString()).isEqualTo("2026-09-23");
        assertThat(chosen.path("rows")).hasSize(1);

        // latest 없이 부르면 예전처럼 전 기간 (하위 호환)
        assertThat(authorizedGet(base).path("rows")).hasSize(3);
        jdbc.update("DELETE FROM observations WHERE dataset = ?", Datasets.OBS_RADAR);
    }

    private void insertRadar(String date, String investor, String code, double netAmountEok) {
        String entity = "KOSPI|" + investor + "|순매수|TODAY|" + code;
        String payload = """
                {"code":"%s","name":"종목%s","netAmountEok":%s,"market":"KOSPI","investor":"%s",
                 "tradeType":"순매수","intervalType":"TODAY","entity":"%s"}
                """.formatted(code, code, netAmountEok, investor, entity);
        jdbc.update("INSERT INTO observations (dataset, obs_date, entity, payload) VALUES (?, ?, ?, ?::jsonb)",
                Datasets.OBS_RADAR, java.sql.Date.valueOf(date), entity, payload);
    }

    @Test
    @DisplayName("태스크별 최근 실행: 태스크마다 마지막에 기록한 1건을 이름 순으로 준다")
    void taskSummaryIsLastRecordedRowPerTask() {
        // 쿼리를 DISTINCT ON(전체 정렬)에서 태스크별 인덱스 첫 행 조회로 바꿨으므로 결과를 고정합니다.
        String prefix = "it_summary_";
        jdbc.update("DELETE FROM collector_task_runs WHERE task LIKE ?", prefix + "%");
        try {
            insertTaskRun(prefix + "radar", "ok", "2026-09-01T00:00:00Z");
            insertTaskRun(prefix + "fx", "error", "2026-09-01T00:05:00Z");
            insertTaskRun(prefix + "radar", "error", "2026-09-01T00:10:00Z");
            insertTaskRun(prefix + "fx", "ok", "2026-09-01T00:01:00Z");    // 시작은 더 이르지만 나중에 기록
            insertTaskRun(prefix + "cot", "empty", "2026-09-01T00:03:00Z");

            List<Map<String, Object>> mine = repository.readTaskSummary().stream()
                    .filter(row -> String.valueOf(row.get("task")).startsWith(prefix))
                    .toList();
            assertThat(mine).extracting(row -> row.get("task") + "=" + row.get("status"))
                    .containsExactly(prefix + "cot=empty", prefix + "fx=ok", prefix + "radar=error");
            assertThat(mine.get(0).keySet()).containsExactly(
                    "task", "speed", "status", "started_at", "duration_ms", "detail", "run_id");
        } finally {
            jdbc.update("DELETE FROM collector_task_runs WHERE task LIKE ?", prefix + "%");
        }
    }

    @Test
    @DisplayName("수집기가 꺼져 DB에서 읽어도 상태 화면은 수집기와 같은 모양(camelCase)으로 받는다")
    void statusFallbackUsesTheCollectorShape() {
        // 예전에는 DB 행을 그대로 내보내(started_at·ok_count …) 화면이 "기록 없음 · 0 / 0 · NaNs"를
        // 그렸습니다. 이 테스트의 수집기 주소(localhost:1)는 닿지 않으므로 DB 폴백 경로를 탑니다.
        String prefix = "it_fallback_";
        Long runId = jdbc.queryForObject(
                "INSERT INTO collector_runs (started_at, finished_at, status, ok_count, fail_count, detail, "
                        + "pid, host, heartbeat_at, group_name) VALUES (now() - interval '10 minutes', "
                        + "now() - interval '9 minutes', 'partial', 17, 2, 'fsc_prices 실패', 1, 'it-host', "
                        + "now() - interval '9 minutes', 'slow') RETURNING id", Long.class);
        try {
            jdbc.update("INSERT INTO collector_task_runs (run_id, task, speed, status, started_at, duration_ms, detail) "
                    + "VALUES (?, ?, 'slow', 'ok', now() - interval '10 minutes', 1234, '12/12')", runId, prefix + "fred");

            JsonNode status = authorizedGet("/api/status");
            assertThat(status.path("collectorReachable").asBoolean(true)).isFalse();
            assertThat(status.path("lastRunStatus").asString()).isEqualTo("partial");
            JsonNode lastRun = status.path("lastRun");
            assertThat(lastRun.path("id").asLong()).isEqualTo(runId);
            assertThat(lastRun.path("okCount").asInt()).isEqualTo(17);
            assertThat(lastRun.path("failCount").asInt()).isEqualTo(2);
            assertThat(lastRun.path("groupName").asString()).isEqualTo("slow");
            assertThat(java.time.Instant.parse(lastRun.path("startedAt").asString())).isNotNull();
            assertThat(lastRun.has("ok_count") || lastRun.has("started_at")).isFalse();

            JsonNode task = null;
            for (JsonNode row : status.path("taskSummary")) {
                if ((prefix + "fred").equals(row.path("task").asString())) {
                    task = row;
                }
            }
            assertThat(task).isNotNull();
            assertThat(task.path("durationMs").asInt()).isEqualTo(1234);
            assertThat(task.path("runId").asLong()).isEqualTo(runId);
            assertThat(java.time.Instant.parse(task.path("startedAt").asString())).isNotNull();
            assertThat(task.has("duration_ms") || task.has("started_at")).isFalse();

            JsonNode history = authorizedGet("/api/status/history?task=" + prefix + "fred&limit=1").path("history");
            assertThat(history).hasSize(1);
            assertThat(history.get(0).path("durationMs").asInt()).isEqualTo(1234);
            assertThat(history.get(0).has("started_at")).isFalse();
        } finally {
            jdbc.update("DELETE FROM collector_task_runs WHERE task LIKE ?", prefix + "%");
            jdbc.update("DELETE FROM collector_runs WHERE id = ?", runId);
        }
    }

    private void insertTaskRun(String task, String status, String startedAt) {
        jdbc.update("INSERT INTO collector_task_runs (task, speed, status, started_at, duration_ms) "
                + "VALUES (?, 'fast', ?, ?::timestamptz, 1)", task, status, startedAt);
    }

    @Test
    @DisplayName("수집기가 없으면 수동 실행은 502 — 200에 ok:false로 숨기지 않는다")
    void manualRunWithoutCollectorIsBadGateway() {
        ResponseEntity<JsonNode> response = authorizedExchange("/api/status/run/sec_13f", HttpMethod.POST);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().path("error").asString()).isEqualTo("bad_gateway");
        assertThat(response.getBody().path("message").asString()).contains("수집기");
    }

    @Test
    @DisplayName("없는 API·허용되지 않는 메서드도 같은 오류 형식으로 답한다")
    void unknownPathsAndMethodsUseTheSameErrorShape() {
        ResponseEntity<JsonNode> notFound = authorizedExchange("/api/does-not-exist", HttpMethod.GET);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getBody().path("error").asString()).isEqualTo("not_found");
        assertThat(notFound.getBody().path("message").asString()).isNotBlank();

        ResponseEntity<JsonNode> wrongMethod = authorizedExchange("/api/verification", HttpMethod.GET);
        assertThat(wrongMethod.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(wrongMethod.getBody().path("error").asString()).isEqualTo("method_not_allowed");
    }

    @Test
    @DisplayName("AI 대기 한도 설정이 실제로 적용된다")
    void aiTimeoutPropertyIsInjected() {
        // 생성자가 둘인데 아무 표시가 없으면 Spring이 무인자 쪽을 골라
        // AI_TIMEOUT_SECONDS가 조용히 무시됩니다. 실제 빈으로 확인합니다.
        assertThat(ai.timeoutSeconds()).isEqualTo(333);
    }

    private JsonNode authorizedGet(String path) {
        ResponseEntity<JsonNode> response = authorizedExchange(path, HttpMethod.GET);

        assertThat(response.getStatusCode())
                .as("%s 응답 상태", path)
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** 상태 코드를 검사하지 않고 그대로 돌려줍니다(오류 응답을 확인할 때). */
    private ResponseEntity<JsonNode> authorizedExchange(String path, HttpMethod method) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie);
        return rest.exchange(url(path), method, new HttpEntity<>(null, headers), JsonNode.class);
    }

    private void insertSnapshot(String name, String payload) {
        insertSnapshot(name, payload, "ok");
    }

    private void insertSnapshot(String name, String payload, String status) {
        jdbc.update(
                "INSERT INTO snapshots (name, payload, kind, status, collected_at) "
                        + "VALUES (?, ?::jsonb, 'json', ?, now()) "
                        + "ON CONFLICT (name) DO UPDATE SET payload = EXCLUDED.payload, "
                        + "status = EXCLUDED.status, collected_at = EXCLUDED.collected_at",
                name, payload, status);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
