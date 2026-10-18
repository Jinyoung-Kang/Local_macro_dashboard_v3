package com.macrodash.feature.alert;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.macrodash.read.StoreReader;
import com.macrodash.store.AlertStore;
import com.macrodash.store.Datasets;
import com.macrodash.store.RegimeLogStore;
import com.macrodash.store.StoreRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import tools.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 알림 평가의 단계별 실패 — 저장본·알림 저장소 모두 목(DB 없이). 트리거 쪽 재현은 {@code AlertEvaluatorDbTest}에 있습니다.
 */
class AlertEvaluatorFailureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant RULE_CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final String NORGES = "0001374170";

    private final Logger logger = (Logger) LoggerFactory.getLogger(AlertEvaluator.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private static AlertEvaluator evaluator(AlertStore alerts, RegimeLogStore regimeLog) {
        return evaluator(alerts, regimeLog, quietStore());
    }

    private static AlertEvaluator evaluator(AlertStore alerts, RegimeLogStore regimeLog, StoreReader store) {
        Clock clock = Clock.fixed(Instant.parse("2026-08-20T03:00:00Z"), ZoneOffset.UTC);
        return new AlertEvaluator(alerts, regimeLog, store, mock(StoreRepository.class), MAPPER, clock);
    }

    /** 저장본은 하나도 없음(읽으면 빈 값) — 트리거가 돌았는지는 읽기 호출로 확인합니다. */
    @SuppressWarnings("unchecked")
    private static StoreReader quietStore() {
        StoreReader store = mock(StoreReader.class);
        when(store.quietly(any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(0)).get());
        return store;
    }

    /** 종류마다 켜진 규칙 하나와 배달할 최근 사건 하나(사용자 7). 사건 번호는 국면 10, 연속 11, 13F 12. */
    private static AlertStore alertsWithOneEventPerKind() {
        AlertStore alerts = mock(AlertStore.class);
        when(alerts.ready()).thenReturn(true);
        when(alerts.enabledRules("REGIME_CHANGE")).thenReturn(List.of(rule(1L, "REGIME_CHANGE", "{}")));
        when(alerts.enabledRules("FOREIGN_STREAK"))
                .thenReturn(List.of(rule(2L, "FOREIGN_STREAK", "{\"target\":\"KOSPI\",\"n\":3}")));
        when(alerts.enabledRules("INSTITUTION_NEW"))
                .thenReturn(List.of(rule(3L, "INSTITUTION_NEW", "{\"cik\":\"" + NORGES + "\"}")));
        when(alerts.recentEvents("REGIME_CHANGE", AlertEvaluator.REFAN_DAYS)).thenReturn(List.of(new AlertStore.Event(
                10L, "REGIME_CHANGE", "regime:2026-08-19:EXPANSION>LATE", LocalDate.of(2026, 8, 19), "t", "b")));
        when(alerts.recentEvents("FOREIGN_STREAK", AlertEvaluator.REFAN_DAYS)).thenReturn(List.of(new AlertStore.Event(
                11L, "FOREIGN_STREAK", "streak:KOSPI:2026-08-19:3", LocalDate.of(2026, 8, 19), "t", "b")));
        when(alerts.recentEvents("INSTITUTION_NEW", AlertEvaluator.REFAN_DAYS)).thenReturn(List.of(new AlertStore.Event(
                12L, "INSTITUTION_NEW", "13f:" + NORGES + ":2026-08-18", LocalDate.of(2026, 8, 18), "t", "b")));
        when(alerts.deliver(10L, 7L, 1L, false)).thenReturn(true);
        when(alerts.deliver(11L, 7L, 2L, false)).thenReturn(true);
        when(alerts.deliver(12L, 7L, 3L, false)).thenReturn(true);
        return alerts;
    }

    private static AlertStore.Rule rule(long id, String kind, String params) {
        return new AlertStore.Rule(id, 7L, kind, MAPPER.readTree(params), true, RULE_CREATED);
    }

    @Test
    @DisplayName("QA-129 한 종류의 배달이 실패해도 다른 종류의 배달과 알림함 정리는 계속한다")
    void oneFanOutFailureDoesNotStopTheOthers() {
        AlertStore alerts = mock(AlertStore.class);
        when(alerts.ready()).thenReturn(true);
        when(alerts.enabledRules("REGIME_CHANGE")).thenReturn(List.of(rule(1L, "REGIME_CHANGE", "{}")));
        when(alerts.enabledRules("FOREIGN_STREAK"))
                .thenReturn(List.of(rule(2L, "FOREIGN_STREAK", "{\"target\":\"KOSPI\",\"n\":3}")));
        when(alerts.enabledRules("INSTITUTION_NEW"))
                .thenReturn(List.of(rule(3L, "INSTITUTION_NEW", "{\"cik\":\"" + NORGES + "\"}")));
        when(alerts.recentEvents("REGIME_CHANGE", AlertEvaluator.REFAN_DAYS))
                .thenThrow(new IllegalStateException("국면 사건 읽기 실패(시험)"));
        when(alerts.recentEvents("FOREIGN_STREAK", AlertEvaluator.REFAN_DAYS)).thenReturn(List.of(new AlertStore.Event(
                11L, "FOREIGN_STREAK", "streak:KOSPI:2026-08-19:3", LocalDate.of(2026, 8, 19), "t", "b")));
        when(alerts.recentEvents("INSTITUTION_NEW", AlertEvaluator.REFAN_DAYS)).thenReturn(List.of(new AlertStore.Event(
                12L, "INSTITUTION_NEW", "13f:" + NORGES + ":2026-08-18", LocalDate.of(2026, 8, 18), "t", "b")));
        when(alerts.deliver(11L, 7L, 2L, false)).thenReturn(true);
        when(alerts.deliver(12L, 7L, 3L, false)).thenReturn(true);
        appender.start();
        logger.addAppender(appender);

        int delivered = evaluator(alerts, mock(RegimeLogStore.class)).evaluate();

        assertThat(delivered).as("국면 배달만 건너뛰고 연속·13F는 배달").isEqualTo(2);
        verify(alerts).purgeDeliveries(AlertEvaluator.INBOX_KEEP_DAYS);
        assertThat(appender.list).as("어느 단계가 왜 실패했는지 경고에 남김").singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("REGIME_CHANGE 배달", "국면 사건 읽기 실패(시험)");
        });
    }

    @Test
    @DisplayName("DB에 닿지 못하면 그 주기는 한 번에 멈춘다 — 단계마다 연결을 기다리며 같은 경고를 되풀이하지 않음")
    void unreachableDatabaseStopsTheCycleAtOnce() {
        AlertStore alerts = mock(AlertStore.class);
        when(alerts.ready()).thenReturn(true);   // 테이블 확인은 한 번 하면 다시 묻지 않습니다(SchemaCheck)
        when(alerts.enabledRules(anyString()))
                .thenThrow(new DataAccessResourceFailureException("연결을 얻지 못함(시험)"));
        RegimeLogStore regimeLog = mock(RegimeLogStore.class);
        when(regimeLog.ready()).thenReturn(true);
        AlertEvaluator evaluator = evaluator(alerts, regimeLog);

        assertThatThrownBy(evaluator::evaluate).isInstanceOf(DataAccessResourceFailureException.class);

        verify(alerts, times(1)).enabledRules(anyString());
        verify(alerts, never()).purgeDeliveries(anyInt());
    }

    // 격리와 중단의 경계 — DataAccessException이라고 다 주기를 멈추면 가장 흔한 단계별 실패(스키마가 바뀐 표 하나,
    // 평가 도중 지워진 규칙·사용자의 FK 위반)가 다시 모든 알림을 멈춥니다(QA-129). 멈추는 것은 DB를 쓸 수 없는
    // 실패(DataAccessResourceFailureException)뿐입니다.

    @Test
    @DisplayName("QA-129 연결 문제가 아닌 DB 오류(SQL 문법)는 그 트리거만 건너뛴다 — 다른 트리거·모든 배달·정리는 계속")
    void sqlErrorInOneTriggerIsIsolated() {
        AlertStore alerts = alertsWithOneEventPerKind();
        RegimeLogStore regimeLog = mock(RegimeLogStore.class);
        when(regimeLog.ready()).thenReturn(true);
        when(regimeLog.recent(anyInt())).thenThrow(new BadSqlGrammarException("판정 기록 읽기",
                "SELECT verdict_date, code, label, inputs FROM regime_log",
                new SQLException("column \"inputs\" does not exist(시험)", "42703")));
        StoreReader store = quietStore();
        appender.start();
        logger.addAppender(appender);

        int delivered = evaluator(alerts, regimeLog, store).evaluate();

        verify(store).read(eq(Datasets.SNAP_TOSS_MARKET_FLOWS), anyLong(), anyString());   // 연속 트리거
        verify(store).read(eq(Datasets.sec13f(NORGES, Datasets.MAX_TRACKED_QUARTERS)), anyLong(), anyString());   // 13F
        assertThat(delivered).as("이미 있던 사건은 세 종류 모두 배달").isEqualTo(3);
        verify(alerts).purgeDeliveries(AlertEvaluator.INBOX_KEEP_DAYS);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("REGIME_CHANGE 사건", "BadSqlGrammarException");
            assertThat(event.getThrowableProxy()).as("DB 오류는 한 줄만 — 5분마다 스택을 쌓지 않음").isNull();
        });
    }

    @Test
    @DisplayName("QA-129 연결 문제가 아닌 DB 오류(제약 위반)는 그 종류의 배달만 건너뛴다 — 다른 종류의 배달·정리는 계속")
    void constraintViolationInOneDeliveryIsIsolated() {
        AlertStore alerts = alertsWithOneEventPerKind();
        // 평가 도중 규칙이나 사용자가 지워지면 알림함 넣기가 FK(23503)에 걸립니다.
        when(alerts.deliver(10L, 7L, 1L, false)).thenThrow(new DataIntegrityViolationException(
                "insert or update on table \"alert_deliveries\" violates foreign key constraint(시험)"));
        appender.start();
        logger.addAppender(appender);

        int delivered = evaluator(alerts, mock(RegimeLogStore.class)).evaluate();

        assertThat(delivered).as("국면 배달만 건너뛰고 연속·13F는 배달").isEqualTo(2);
        verify(alerts).deliver(11L, 7L, 2L, false);
        verify(alerts).deliver(12L, 7L, 3L, false);
        verify(alerts).purgeDeliveries(AlertEvaluator.INBOX_KEEP_DAYS);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("REGIME_CHANGE 배달", "DataIntegrityViolationException");
            assertThat(event.getThrowableProxy()).as("DB 오류는 한 줄만 — 5분마다 스택을 쌓지 않음").isNull();
        });
    }

    @Test
    @DisplayName("코드 결함(NPE 등)은 스택 트레이스를 함께 남긴다 — 경고 한 줄로는 어느 파일·줄인지 알 수 없음")
    void codeDefectWarningCarriesTheStackTrace() {
        AlertStore alerts = alertsWithOneEventPerKind();
        // 사건 키가 빈 사건 — 규칙과 맞춰 보는 코드(matches)에서 NPE가 납니다(아직 모르는 코드 결함의 자리).
        when(alerts.recentEvents("FOREIGN_STREAK", AlertEvaluator.REFAN_DAYS)).thenReturn(List.of(new AlertStore.Event(
                11L, "FOREIGN_STREAK", null, LocalDate.of(2026, 8, 19), "t", "b")));
        appender.start();
        logger.addAppender(appender);

        int delivered = evaluator(alerts, mock(RegimeLogStore.class)).evaluate();

        assertThat(delivered).as("연속 배달만 건너뛰고 국면·13F는 배달").isEqualTo(2);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("FOREIGN_STREAK 배달", "NullPointerException");
            IThrowableProxy thrown = event.getThrowableProxy();
            assertThat(thrown).as("스택 트레이스가 붙음").isNotNull();
            assertThat(thrown.getClassName()).isEqualTo(NullPointerException.class.getName());
            StackTraceElement top = thrown.getStackTraceElementProxyArray()[0].getStackTraceElement();
            assertThat(top.getClassName() + "." + top.getMethodName()).as("결함이 난 곳")
                    .isEqualTo(AlertEvaluator.class.getName() + ".matches");
        });
    }

    @Test
    @DisplayName("연결을 얻지 못하면(Hikari 시간 초과) 배달 도중이라도 그 주기를 한 번에 멈춘다")
    void connectionTimeoutStopsTheCycleAtOnce() {
        AlertStore alerts = alertsWithOneEventPerKind();
        // 실제 경로: Hikari가 connection-timeout 뒤 SQLTransientConnectionException → DataSourceUtils가 감쌈.
        when(alerts.deliver(10L, 7L, 1L, false)).thenThrow(new CannotGetJdbcConnectionException(
                "Failed to obtain JDBC Connection", new SQLTransientConnectionException(
                        "HikariPool-1 - Connection is not available, request timed out after 5000ms(시험)")));
        appender.start();
        logger.addAppender(appender);
        AlertEvaluator evaluator = evaluator(alerts, mock(RegimeLogStore.class));

        assertThatThrownBy(evaluator::evaluate).isInstanceOf(CannotGetJdbcConnectionException.class);

        verify(alerts, never()).recentEvents(eq("FOREIGN_STREAK"), anyInt());
        verify(alerts, never()).recentEvents(eq("INSTITUTION_NEW"), anyInt());
        verify(alerts, never()).purgeDeliveries(anyInt());
        assertThat(appender.list).as("단계 경고 없이 바깥(scheduledEvaluate)이 한 줄만 남김").isEmpty();
    }
}
