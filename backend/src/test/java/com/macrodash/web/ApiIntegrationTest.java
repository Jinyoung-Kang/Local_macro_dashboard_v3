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
        "dashboard.jwt-secret=integration-test-secret-key-32-bytes!",
        "dashboard.read-mode=store_only",
        "dashboard.collector-url=http://localhost:1",    // 수집기가 없어도 화면은 떠야 합니다
        "dashboard.ai.timeout-seconds=333"               // 설정 주입이 실제로 먹는지 확인용
})
class ApiIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    com.macrodash.service.AiService ai;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.macrodash.store.StoreRepository repository;

    private String sessionCookie;

    @BeforeEach
    void setUp() {
        guardAgainstRealDatabase();
        jdbc.execute("DELETE FROM snapshots");
        jdbc.update("DELETE FROM observations WHERE dataset = ?", Datasets.OBS_TOSS_STOCK_FLOW);
        sessionCookie = login("test-password");
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
    @DisplayName("로그아웃한 세션 쿠키는 만료 전이라도 다시 쓸 수 없다 (SEC-07)")
    void loggedOutSessionCannotBeReused() {
        // 예전에는 로그아웃이 브라우저 쿠키만 지워서, 쿠키 값을 가진 쪽은 12시간 동안 계속 쓸 수 있었습니다.
        String stolen = login("test-password");
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
    @DisplayName("잘못된 비밀번호는 거부된다")
    void rejectsWrongPassword() {
        ResponseEntity<String> response = rest.postForEntity(
                url("/api/auth/login"), Map.of("password", "wrong"), String.class);

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

    // ------------------------------------------------------------------ helpers
    private String login(String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = rest.postForEntity(
                url("/api/auth/login"),
                new HttpEntity<>(Map.of("password", password), headers),
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
