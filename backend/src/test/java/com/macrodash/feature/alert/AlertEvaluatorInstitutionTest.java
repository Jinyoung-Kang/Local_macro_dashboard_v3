package com.macrodash.feature.alert;

import com.macrodash.read.StoreReader;
import com.macrodash.store.AlertStore;
import com.macrodash.store.Datasets;
import com.macrodash.store.RegimeLogStore;
import com.macrodash.store.Snapshot;
import com.macrodash.store.StoreRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 13F 신규 편입 알림 — 저장본·알림 저장소 모두 목(DB 없이).
 */
class AlertEvaluatorInstitutionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NORGES = "0001374170";

    private static AlertEvaluator evaluator(AlertStore alerts, String quartersJson) {
        StoreReader store = mock(StoreReader.class);
        when(store.read(anyString(), anyLong(), anyString())).thenReturn(Optional.empty());
        when(store.read(eq(Datasets.sec13f(NORGES, Datasets.MAX_TRACKED_QUARTERS)), anyLong(), anyString()))
                .thenReturn(Optional.of(new Snapshot(Datasets.sec13f(NORGES, Datasets.MAX_TRACKED_QUARTERS),
                        MAPPER.readTree("{\"cik\":\"" + NORGES + "\",\"quarters\":" + quartersJson + "}"),
                        "json", "ok", null, Instant.now())));
        Clock clock = Clock.fixed(Instant.parse("2026-08-20T03:00:00Z"), ZoneOffset.UTC);
        return new AlertEvaluator(alerts, mock(RegimeLogStore.class), store, mock(StoreRepository.class), MAPPER, clock);
    }

    private static AlertStore alertsWatchingNorges() {
        AlertStore alerts = mock(AlertStore.class);
        when(alerts.enabledRules("INSTITUTION_NEW")).thenReturn(List.of(new AlertStore.Rule(
                1L, 1L, "INSTITUTION_NEW", MAPPER.readTree("{\"cik\":\"" + NORGES + "\"}"), true, Instant.now())));
        return alerts;
    }

    @Test
    @Disabled("QA-085: 수정 전 재현 — fix 커밋에서 켬")
    @DisplayName("QA-085 직전 분기(2026-03-31)가 저장돼 있지 않으면 6개월 전과 비교해 '신규 편입' 알림을 만들지 않는다")
    void noNewPositionEventAcrossAMissingQuarter() {
        AlertStore alerts = alertsWatchingNorges();

        evaluator(alerts, """
                [{"reportDate":"2026-06-30","filingDate":"2026-08-12","holdings":[
                    {"name":"X CORP","cusip":"X00000001","class":"COM","value":100,"shares":10,"weight":100.0}]},
                 {"reportDate":"2025-12-31","filingDate":"2026-02-10","holdings":[
                    {"name":"Y CORP","cusip":"Y00000001","class":"COM","value":100,"shares":10,"weight":100.0}]}]
                """).institutionEvents();

        verify(alerts, never()).insertEvent(anyString(), anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("바로 앞 분기와 비교할 수 있으면 신규 편입 알림을 하나 만든다(대조군)")
    void newPositionEventForConsecutiveQuarters() {
        AlertStore alerts = alertsWatchingNorges();

        evaluator(alerts, """
                [{"reportDate":"2026-06-30","filingDate":"2026-08-12","holdings":[
                    {"name":"X CORP","cusip":"X00000001","class":"COM","value":100,"shares":10,"weight":100.0}]},
                 {"reportDate":"2026-03-31","filingDate":"2026-05-11","holdings":[
                    {"name":"Y CORP","cusip":"Y00000001","class":"COM","value":100,"shares":10,"weight":100.0}]}]
                """).institutionEvents();

        verify(alerts).insertEvent(eq("INSTITUTION_NEW"), eq("13f:" + NORGES + ":2026-08-12"), any(),
                anyString(), anyString(), any());
    }
}
