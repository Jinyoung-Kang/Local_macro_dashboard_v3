package com.macrodash.feature.alert;

import com.macrodash.read.StoreReader;
import com.macrodash.store.AlertStore;
import com.macrodash.store.RegimeLogStore;
import com.macrodash.store.SchemaCheck;
import com.macrodash.store.StoreRepository;
import com.macrodash.store.UserStore;
import com.macrodash.support.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 알림 평가 — 실제 테스트 DB. 국면 전환이 확정되면 사건 하나·알림함 한 건, 다시 평가해도 그대로, 규칙을 끄면 새 사건이
 * 들어오지 않는다. 다른 테스트·기록과 겹치지 않게 2099년 날짜와 고유 이메일만 쓰고 그것만 지웁니다.
 */
class AlertEvaluatorDbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LocalDate DAY = LocalDate.of(2099, 3, 2);
    private static final String EMAIL = "alerteval-user@test.local";

    private JdbcTemplate jdbc;
    private AlertStore alerts;
    private RegimeLogStore regimeLog;
    private long userId;

    @BeforeEach
    void setUp() {
        String url = System.getenv().getOrDefault("TEST_DATABASE_URL", "jdbc:postgresql://localhost:5432/macrodash_test");
        assertThat(url).endsWith("_test");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv().getOrDefault("DATABASE_USER", "macro"),
                System.getenv().getOrDefault("DATABASE_PASSWORD", "macro")));
        SchemaCheck schema = new SchemaCheck(jdbc);
        alerts = new AlertStore(jdbc, MAPPER, schema);
        regimeLog = new RegimeLogStore(jdbc, MAPPER, schema);
        cleanUp();
        userId = new UserStore(jdbc, schema).insert(EMAIL, "x", "MEMBER", "PRO", true);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM alert_events WHERE event_key LIKE 'regime:2099-%'");
        jdbc.update("DELETE FROM regime_log WHERE verdict_date >= '2099-01-01'");
        jdbc.update("DELETE FROM users WHERE email = ?", EMAIL);
    }

    @SuppressWarnings("unchecked")
    private AlertEvaluator evaluator() {
        StoreReader store = mock(StoreReader.class);
        when(store.quietly(any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(0)).get());
        Clock clock = Clock.fixed(Instant.parse("2099-03-12T03:00:00Z"), ZoneOffset.UTC);
        return new AlertEvaluator(alerts, regimeLog, store, mock(StoreRepository.class), MAPPER, clock);
    }

    private void log(int day, String code, String label) {
        regimeLog.insertIfAbsent(new RegimeLogStore.Entry(DAY.plusDays(day), code, label,
                MAPPER.valueToTree(Map.of("nfci", Map.of("value", -0.4, "asOf", DAY.toString()))), List.of(), "v1"));
    }

    @Test
    @DisplayName("국면이 3일 이어져 확정되면 알림함에 한 건 — 다시 평가해도 그대로, 하루 바뀐 것은 알리지 않음")
    void regimeChangeIsDeliveredOnce() {
        new AlertService(alerts, MAPPER).create(new CurrentUser(userId, EMAIL, "MEMBER", "PRO", null),
                "REGIME_CHANGE", MAPPER.createObjectNode());
        for (int i = 0; i < 3; i++) {
            log(i, "EXPANSION", "확장 (Expansion)");
        }
        log(3, "LATE", "후퇴 경계 (Late cycle)");              // 하루만 바뀜 — 알리지 않음
        for (int i = 4; i < 7; i++) {
            log(i, "EXPANSION", "확장 (Expansion)");
        }
        for (int i = 7; i < 10; i++) {
            log(i, "CONTRACTION", "침체 경계 (Contraction)");
        }

        AlertEvaluator evaluator = evaluator();
        assertThat(evaluator.evaluate()).isEqualTo(1);
        assertThat(evaluator.evaluate()).as("다시 평가해도 새로 넣지 않음").isZero();

        List<AlertStore.InboxItem> inbox = alerts.inbox(userId, 10);
        assertThat(inbox).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("[국면 전환] 확장 → 침체 경계");
            assertThat(item.body()).contains("2099-03-11", "3일 연속", "투자 권유가 아닙니다");
            assertThat(item.late()).isFalse();
        });
        assertThat(alerts.unreadCount(userId)).isEqualTo(1);
        long itemId = inbox.get(0).id();
        assertThat(alerts.markRead(userId + 1_000_000, List.of(itemId))).as("다른 사용자의 항목은 건드리지 않음").isZero();
        assertThat(alerts.markRead(userId, List.of(itemId, -1L))).as("고른 것만 한 문장으로").isEqualTo(1);
        assertThat(alerts.unreadCount(userId)).isZero();
        assertThat(alerts.markRead(userId, null)).as("이미 읽음").isZero();
    }

    @Test
    @DisplayName("규칙을 끄면 그 뒤 사건은 알림함에 들어오지 않는다")
    void disabledRuleGetsNothing() {
        CurrentUser user = new CurrentUser(userId, EMAIL, "MEMBER", "PRO", null);
        AlertService service = new AlertService(alerts, MAPPER);
        service.create(user, "REGIME_CHANGE", MAPPER.createObjectNode());
        long ruleId = alerts.rules(userId).get(0).id();
        service.setEnabled(user, ruleId, false);
        for (int i = 0; i < 3; i++) {
            log(i, "EXPANSION", "확장 (Expansion)");
        }
        for (int i = 3; i < 6; i++) {
            log(i, "LATE", "후퇴 경계 (Late cycle)");
        }

        assertThat(evaluator().evaluate()).isZero();
        assertThat(alerts.inbox(userId, 10)).isEmpty();
    }

    @Test
    @DisplayName("규칙을 만든 날보다 앞선 사건은 보내지 않는다 — 처음 켤 때 과거 사건을 몰아 보내지 않음")
    void noBackfill() {
        for (int i = 0; i < 3; i++) {
            log(i, "EXPANSION", "확장 (Expansion)");
        }
        for (int i = 3; i < 6; i++) {
            log(i, "LATE", "후퇴 경계 (Late cycle)");
        }
        long rule = alerts.insertRule(userId, "REGIME_CHANGE", MAPPER.createObjectNode());
        jdbc.update("UPDATE alert_rules SET created_at = '2099-03-15T00:00:00Z' WHERE id = ?", rule);

        assertThat(evaluator().evaluate()).isZero();
        Integer events = jdbc.queryForObject("SELECT COUNT(*) FROM alert_events WHERE event_key LIKE 'regime:2099-%'",
                Integer.class);
        assertThat(events).as("사건은 기록하되 알림함에는 넣지 않음").isEqualTo(1);
    }

    @Test
    @DisplayName("사건 발생 3일 뒤에야 들어가면 '지난 사건' — 읽지 않은 수에 세지 않는다(백엔드가 꺼져 있었던 경우)")
    @SuppressWarnings("unchecked")
    void lateEventsAreMarked() {
        alerts.insertRule(userId, "REGIME_CHANGE", MAPPER.createObjectNode());
        for (int i = 0; i < 3; i++) {
            log(i, "EXPANSION", "확장 (Expansion)");
        }
        for (int i = 3; i < 6; i++) {
            log(i, "LATE", "후퇴 경계 (Late cycle)");
        }
        StoreReader store = mock(StoreReader.class);
        when(store.quietly(any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(0)).get());
        Clock muchLater = Clock.fixed(Instant.parse("2099-03-20T03:00:00Z"), ZoneOffset.UTC);
        new AlertEvaluator(alerts, regimeLog, store, mock(StoreRepository.class), MAPPER, muchLater).evaluate();

        assertThat(alerts.inbox(userId, 10)).singleElement().satisfies(item -> assertThat(item.late()).isTrue());
        assertThat(alerts.unreadCount(userId)).isZero();
    }
}
