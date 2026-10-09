package com.macrodash.feature.insight;

import com.macrodash.config.AppProperties;
import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA-015: 국면 이력의 평가 요일이 고정돼 있지 않다.
 *
 * <p>주간 날짜를 "기간 시작일(오늘 − N년) 또는 금리차 첫 날짜"에서 7일씩 더해 만듭니다. 그래서 기본 5년
 * 보기에서는 <b>날마다 평가 요일이 바뀌고</b>, 토·일요일 날짜가 이력과 구간 경계에 그대로 찍힙니다.
 * 여기서는 금리차 첫 날짜를 토요일로 두어 오늘 날짜와 무관하게 재현합니다.
 */
class RegimeTimelineQaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Optional<Snapshot> points(String name, LocalDate from, int days, double value) {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode array = payload.putArray("points");
        for (int i = 0; i < days; i++) {
            array.addObject().put("date", from.plusDays(i).toString()).put("value", value);
        }
        return Optional.of(new Snapshot(name, payload, "json", "ok", null, Instant.now()));
    }

    @Test
    @DisplayName("QA-015: 국면 이력은 매주 목요일(WALCL 발표일)로 평가한다")
    @SuppressWarnings("unchecked")
    void timelineUsesFixedWeekday() {
        LocalDate saturday = LocalDate.of(2025, 1, 4);
        StoreReader store = mock(StoreReader.class);
        when(store.readMode()).thenReturn(AppProperties.ReadMode.STORE_ONLY);
        when(store.read(anyString(), anyLong(), anyString())).thenReturn(Optional.empty());
        when(store.read(eq(Datasets.fredSeries("T10Y3M")), anyLong(), anyString()))
                .thenReturn(points(Datasets.fredSeries("T10Y3M"), saturday, 120, 0.5));

        Map<String, Object> regime = new AnalyticsService(store).regime(5);

        List<Map<String, Object>> timeline = (List<Map<String, Object>>) regime.get("timeline");
        assertThat(timeline).isNotEmpty();
        assertThat(timeline).allSatisfy(point ->
                assertThat(LocalDate.parse(String.valueOf(point.get("date"))).getDayOfWeek())
                        .isEqualTo(DayOfWeek.THURSDAY));
    }
}
