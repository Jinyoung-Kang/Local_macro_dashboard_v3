package com.macrodash.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.NavigableMap;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSeriesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("날짜·값 배열은 같은 칸끼리 묶고, 한쪽이 비면 그 칸을 통째로 버린다")
    void dateValueSeriesDropsIncompleteSlots() {
        var entry = MAPPER.readTree("""
                {"dates":["2026-01-02","bad","2026-01-06","2026-01-07"],
                 "close":[100, 101, null, 103]}
                """);
        NavigableMap<LocalDate, Double> series = Json.dateValueSeries(entry, "dates", "close", null);

        assertThat(series).containsExactly(
                java.util.Map.entry(LocalDate.of(2026, 1, 2), 100.0),
                java.util.Map.entry(LocalDate.of(2026, 1, 7), 103.0));
        assertThat(Json.dateValueSeries(entry, "dates", "close", LocalDate.of(2026, 1, 5)))
                .containsOnlyKeys(LocalDate.of(2026, 1, 7));
        assertThat(Json.dateValueSeries(null, "dates", "close", null)).isEmpty();
        assertThat(Json.dateValueSeries(MAPPER.readTree("{\"dates\":\"x\"}"), "dates", "close", null)).isEmpty();
    }

    @Test
    @DisplayName("points 배열은 value 또는 close를 읽고 값이 없는 점은 뺀다")
    void pointSeriesReadsValueOrClose() {
        var payload = MAPPER.readTree("""
                {"points":[{"date":"2026-01-02","value":1.5},{"date":"2026-01-03"},{"date":"2026-01-04","close":2.5}]}
                """);
        assertThat(Json.pointSeries(payload)).containsExactly(
                java.util.Map.entry(LocalDate.of(2026, 1, 2), 1.5),
                java.util.Map.entry(LocalDate.of(2026, 1, 4), 2.5));
    }

    @Test
    @DisplayName("날짜가 깨진 점이 끼어도 뒤의 값이 한 칸씩 밀리지 않는다")
    void pointSeriesDoesNotShiftAfterBrokenDate() {
        // 날짜 목록과 값 목록을 따로 뽑아 zip하면, 날짜가 없는 두 번째 점이 날짜 목록에서만 빠져
        // 1월 4일에 2.0(두 번째 점의 값)이 붙습니다. 점 단위로 읽어야 합니다.
        var payload = MAPPER.readTree("""
                {"points":[{"date":"2026-01-02","value":1.0},{"value":2.0},{"date":"2026-01-04","value":3.0}]}
                """);
        assertThat(Json.pointSeries(payload)).containsExactly(
                java.util.Map.entry(LocalDate.of(2026, 1, 2), 1.0),
                java.util.Map.entry(LocalDate.of(2026, 1, 4), 3.0));
    }
}
