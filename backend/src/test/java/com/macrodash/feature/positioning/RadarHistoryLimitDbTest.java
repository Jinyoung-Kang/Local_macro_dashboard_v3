package com.macrodash.feature.positioning;

import com.macrodash.collector.CollectorClient;
import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.StoreRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 수급 이력 전 기간 모드(latest=false)의 행 상한 — 실제 테스트 DB (QA-133).
 *
 * <p>예전에는 상한 없이 쌓인 기록을 모두 보냈습니다(거래일마다 약 100행씩 끝없이 늘어남). 다른 테스트·기록과
 * 겹치지 않게 이 테스트만 쓰는 시장 이름({@value #MARKET})과 1990년 날짜로 넣고, 그 시장의 행만 지웁니다.
 */
class RadarHistoryLimitDbTest {

    private static final String MARKET = "QA133TEST";
    private static final LocalDate FIRST_DAY = LocalDate.of(1990, 1, 1);
    private static final int DAYS = 50;
    private static final int CODES_PER_DAY = 100;
    private static final int LIMIT = 5_000;

    private JdbcTemplate jdbc;
    private RadarService radar;

    @BeforeEach
    void setUp() {
        String url = System.getenv().getOrDefault("TEST_DATABASE_URL",
                "jdbc:postgresql://localhost:5432/macrodash_test");
        assertThat(url).as("테스트 DB만 씁니다").endsWith("_test");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv().getOrDefault("DATABASE_USER", "macro"),
                System.getenv().getOrDefault("DATABASE_PASSWORD", "macro")));
        radar = new RadarService(mock(StoreReader.class), new StoreRepository(jdbc, new ObjectMapper()),
                mock(CollectorClient.class));
        cleanUp();

        // 외국인 50일 × 100종목 = 정확히 5,000행 + 가장 최근 날(51일째)의 기관 1행 = 이 시장 전체 5,001행.
        List<Object[]> rows = new ArrayList<>();
        for (int day = 0; day < DAYS; day++) {
            for (int code = 0; code < CODES_PER_DAY; code++) {
                rows.add(row(FIRST_DAY.plusDays(day), "외국인", "%06d".formatted(code)));
            }
        }
        rows.add(row(FIRST_DAY.plusDays(DAYS), "기관", "000000"));
        jdbc.batchUpdate("INSERT INTO observations (dataset, obs_date, entity, payload) VALUES (?, ?, ?, ?::jsonb)",
                rows);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM observations WHERE dataset = ? AND entity LIKE ?",
                Datasets.OBS_RADAR, MARKET + "|%");
    }

    private static Object[] row(LocalDate date, String investor, String code) {
        String entity = MARKET + "|" + investor + "|순매수|TODAY|" + code;
        String payload = """
                {"code":"%s","name":"종목%s","netAmountEok":1.0,"market":"%s","investor":"%s",
                 "tradeType":"순매수","intervalType":"TODAY","entity":"%s"}
                """.formatted(code, code, MARKET, investor, entity);
        return new Object[]{Datasets.OBS_RADAR, java.sql.Date.valueOf(date), entity, payload};
    }

    @SuppressWarnings("unchecked")
    private static List<JsonNode> rows(Map<String, Object> out) {
        return (List<JsonNode>) out.get("rows");
    }

    @Test
    @Disabled("QA-133: 수정 전 재현 — fix 커밋에서 켬")
    @DisplayName("전 기간 모드는 최신 5,000행까지만 주고, 잘렸는지(truncated)와 상한(rowLimit)을 함께 알린다")
    void fullHistoryIsCappedAtNewestRows() {
        Map<String, Object> capped = radar.history(MARKET, null, null, null, null, false);
        List<JsonNode> kept = rows(capped);
        assertThat(kept.size()).as("5,001행 중 응답 행 수").isEqualTo(LIMIT);
        assertThat(capped).containsEntry("rowLimit", LIMIT).containsEntry("truncated", true);
        // 최신부터 자릅니다 — 가장 최근 날의 기관 행이 남고, 가장 오래된 날의 마지막 종목이 빠집니다.
        assertThat(kept.get(0).path("obsDate").asString()).isEqualTo(FIRST_DAY.plusDays(DAYS).toString());
        assertThat(kept.get(LIMIT - 1).path("obsDate").asString()).isEqualTo(FIRST_DAY.toString());
        assertThat(kept.get(LIMIT - 1).path("code").asString()).isEqualTo("%06d".formatted(CODES_PER_DAY - 2));

        // 정확히 상한만큼 맞는 조건은 다 받고 잘리지 않았다고 답합니다(limit+1행을 읽어 판단).
        Map<String, Object> exact = radar.history(MARKET, "외국인", null, null, null, false);
        assertThat(rows(exact).size()).as("외국인 5,000행").isEqualTo(LIMIT);
        assertThat(exact).containsEntry("rowLimit", LIMIT).containsEntry("truncated", false);

        // 오래된 날은 obsDate로 하루씩 그대로 받을 수 있습니다.
        Map<String, Object> oldest = radar.history(MARKET, null, null, FIRST_DAY.toString(), null, false);
        assertThat(rows(oldest)).hasSize(CODES_PER_DAY);
        assertThat(oldest).containsEntry("truncated", false);

        // latest=true(화면이 쓰는 모드)는 그대로 — 하루치만, 상한 표시 없음.
        Map<String, Object> latest = radar.history(MARKET, null, null, null, null, true);
        assertThat(rows(latest)).hasSize(1);
        assertThat(latest).doesNotContainKeys("rowLimit", "truncated");
    }
}
