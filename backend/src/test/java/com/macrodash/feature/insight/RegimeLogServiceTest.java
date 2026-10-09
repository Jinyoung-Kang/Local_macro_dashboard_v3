package com.macrodash.feature.insight;

import com.macrodash.analytics.Regime;
import com.macrodash.read.StoreReader;
import com.macrodash.store.RegimeLogStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 국면 판정 라이브 기록 — 언제 기록하고, 무엇을 "판정 불가"로 남기는가.
 */
class RegimeLogServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);

    /** KST 기준 시각에 멈춘 시계. */
    private static Clock kstAt(int hour) {
        return Clock.fixed(Instant.parse("2026-10-09T%02d:30:00Z".formatted(hour - 9)), ZoneOffset.UTC);
    }

    private static Map<String, Object> input(Double value, String asOf) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", value);
        out.put("asOf", asOf);
        return out;
    }

    private static AnalyticsService.RegimeReading reading(String nfciAsOf) {
        Regime.Verdict verdict = Regime.classify(0.42, -0.48, 2.85, 0.025);
        Map<String, Map<String, Object>> inputs = new LinkedHashMap<>();
        inputs.put("curve10y3m", input(0.42, "2026-10-08"));
        inputs.put("nfci", input(-0.48, nfciAsOf));
        inputs.put("hyOas", input(2.85, "2026-10-08"));
        inputs.put("liquidity4w", input(0.025, "2026-10-08"));
        return new AnalyticsService.RegimeReading(verdict, inputs);
    }

    @SuppressWarnings("unchecked")
    private static StoreReader quietStore() {
        StoreReader store = mock(StoreReader.class);
        when(store.quietly(any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(0)).get());
        return store;
    }

    @Test
    @DisplayName("입력이 모두 신선하면 화면과 같은 판정을 그대로 기록한다")
    void freshInputsAreRecordedAsIs() {
        RegimeLogService service = new RegimeLogService(mock(AnalyticsService.class), quietStore(),
                mock(RegimeLogStore.class), MAPPER, kstAt(19));

        RegimeLogStore.Entry entry = service.toEntry(TODAY, reading("2026-10-02"));

        assertThat(entry.code()).isEqualTo("EXPANSION");
        assertThat(entry.missing()).isEmpty();
        assertThat(entry.ruleVersion()).isEqualTo(Regime.RULE_VERSION);
        assertThat(entry.inputs().path("nfci").path("asOf").asText()).isEqualTo("2026-10-02");
    }

    @Test
    @DisplayName("입력이 오래됐으면 '판정 불가'로 남기고 어느 입력이 며칠 지났는지 적는다")
    void staleInputBecomesUnknown() {
        RegimeLogService service = new RegimeLogService(mock(AnalyticsService.class), quietStore(),
                mock(RegimeLogStore.class), MAPPER, kstAt(19));

        RegimeLogStore.Entry entry = service.toEntry(TODAY, reading("2026-09-18"));

        assertThat(entry.code()).isEqualTo("UNKNOWN");
        assertThat(entry.label()).isEqualTo("판정 불가");
        assertThat(entry.missing()).containsExactly("입력 오래됨: NFCI (2026-09-18, 21일 전)");
    }

    @Test
    @DisplayName("KST 18시 전에는 기록하지 않는다 — 밤사이 수집이 아직 안 돌았을 수 있음")
    void waitsUntilEvening() {
        RegimeLogStore regimeLog = mock(RegimeLogStore.class);
        when(regimeLog.ready()).thenReturn(true);
        AnalyticsService analytics = mock(AnalyticsService.class);
        RegimeLogService service = new RegimeLogService(analytics, quietStore(), regimeLog, MAPPER, kstAt(17));

        assertThat(service.recordToday()).isEqualTo(RegimeLogService.Outcome.TOO_EARLY);
        verify(analytics, never()).regimeNow();
    }

    @Test
    @DisplayName("저장본은 quietly로 읽는다(수집을 일으키지 않음). 이미 기록한 날은 다시 계산하지 않는다")
    void recordsOncePerDayWithoutTriggeringCollection() {
        RegimeLogStore regimeLog = mock(RegimeLogStore.class);
        when(regimeLog.ready()).thenReturn(true);
        when(regimeLog.exists(TODAY)).thenReturn(false, true);
        when(regimeLog.insertIfAbsent(any())).thenReturn(true);
        AnalyticsService analytics = mock(AnalyticsService.class);
        when(analytics.regimeNow()).thenReturn(reading("2026-10-02"));
        StoreReader store = quietStore();
        RegimeLogService service = new RegimeLogService(analytics, store, regimeLog, MAPPER, kstAt(19));

        assertThat(service.recordToday()).isEqualTo(RegimeLogService.Outcome.RECORDED);
        assertThat(service.recordToday()).isEqualTo(RegimeLogService.Outcome.ALREADY_RECORDED);

        verify(store).quietly(any());
        verify(analytics).regimeNow();
    }

    @Test
    @DisplayName("테이블이 아직 없으면(수집기가 V3 적용 전) 기록하지 않고 화면에는 이유를 알린다")
    void schemaNotReady() {
        RegimeLogStore regimeLog = mock(RegimeLogStore.class);
        when(regimeLog.ready()).thenReturn(false);
        RegimeLogService service = new RegimeLogService(mock(AnalyticsService.class), quietStore(),
                regimeLog, MAPPER, kstAt(19));

        assertThat(service.recordToday()).isEqualTo(RegimeLogService.Outcome.SCHEMA_NOT_READY);
        Map<String, Object> recent = service.recent(30);
        assertThat(recent.get("available")).isEqualTo(false);
        assertThat(String.valueOf(recent.get("message"))).contains("V3");
    }
}
