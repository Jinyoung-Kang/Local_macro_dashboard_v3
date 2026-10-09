package com.macrodash.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판정 기록 테이블(V3) — 실제 테스트 DB(macrodash_test)에서 "하루 한 건"을 확인합니다.
 *
 * <p>운영 DB로 돌지 않도록 이름이 {@code _test}로 끝나는지 먼저 확인합니다. 다른 테스트·운영 기록과 겹치지 않게
 * 1999년 날짜만 쓰고, 그 날짜만 지웁니다(지우는 것은 테스트뿐 — 저장소에는 지우는 메서드가 없습니다).
 */
class RegimeLogStoreDbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LocalDate DAY = LocalDate.of(1999, 1, 4);

    private JdbcTemplate jdbc;
    private RegimeLogStore store;

    @BeforeEach
    void setUp() {
        String url = System.getenv().getOrDefault("TEST_DATABASE_URL",
                "jdbc:postgresql://localhost:5432/macrodash_test");
        assertThat(url).as("테스트 DB만 씁니다").endsWith("_test");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("DATABASE_USER", "macro"),
                System.getenv().getOrDefault("DATABASE_PASSWORD", "macro"));
        jdbc = new JdbcTemplate(dataSource);
        store = new RegimeLogStore(jdbc, MAPPER, new SchemaCheck(jdbc));
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM regime_log WHERE verdict_date < '2000-01-01'");
    }

    private RegimeLogStore.Entry entry(String code) {
        return new RegimeLogStore.Entry(DAY, code, "확장 (Expansion)",
                MAPPER.valueToTree(Map.of("nfci", Map.of("value", -0.48, "asOf", "1998-12-31"))),
                List.of("입력 오래됨: NFCI (1998-12-18, 17일 전)"), "v1");
    }

    @Test
    @DisplayName("같은 날은 한 건 — 두 번째 기록은 무시되고 첫 기록이 그대로 남는다")
    void oneEntryPerDay() {
        assertThat(store.ready()).isTrue();
        assertThat(store.insertIfAbsent(entry("EXPANSION"))).isTrue();
        assertThat(store.insertIfAbsent(entry("CONTRACTION"))).isFalse();

        RegimeLogStore.Entry saved = store.recent(3650).stream()
                .filter(e -> e.verdictDate().equals(DAY)).findFirst().orElseThrow();
        assertThat(saved.code()).as("나중 값으로 덮어쓰지 않음").isEqualTo("EXPANSION");
        assertThat(saved.missing()).containsExactly("입력 오래됨: NFCI (1998-12-18, 17일 전)");
        assertThat(saved.inputs().path("nfci").path("asOf").asText()).isEqualTo("1998-12-31");
    }

    @Test
    @DisplayName("두 곳에서 동시에 기록해도 한 건 — 재시작이 겹쳐도 중복이 없다")
    void concurrentInsertsLeaveOneRow() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Callable<Boolean> insert = () -> store.insertIfAbsent(entry("EXPANSION"));
            List<Future<Boolean>> results = pool.invokeAll(List.of(insert, insert, insert, insert));
            long inserted = 0;
            for (Future<Boolean> result : results) {
                inserted += result.get() ? 1 : 0;
            }
            assertThat(inserted).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM regime_log WHERE verdict_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertThat(rows).isEqualTo(1);
    }
}
