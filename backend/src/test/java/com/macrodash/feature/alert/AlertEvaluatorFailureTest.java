package com.macrodash.feature.alert;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.macrodash.read.StoreReader;
import com.macrodash.store.AlertStore;
import com.macrodash.store.RegimeLogStore;
import com.macrodash.store.StoreRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;

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
import static org.mockito.ArgumentMatchers.anyString;
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

    @SuppressWarnings("unchecked")
    private static AlertEvaluator evaluator(AlertStore alerts, RegimeLogStore regimeLog) {
        StoreReader store = mock(StoreReader.class);
        when(store.quietly(any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(0)).get());
        Clock clock = Clock.fixed(Instant.parse("2026-08-20T03:00:00Z"), ZoneOffset.UTC);
        return new AlertEvaluator(alerts, regimeLog, store, mock(StoreRepository.class), MAPPER, clock);
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
}
