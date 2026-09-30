package com.macrodash.service;

import com.macrodash.analytics.SeriesMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 날짜 배열과 종가 배열이 어긋나지 않는지 고정합니다.
 *
 * <p>예전에는 둘을 따로 걸러 냈습니다. 종가 한 칸이 null이면 그 칸만 빠지고
 * 날짜는 그대로 남아, 이후 모든 짝이 한 칸씩 밀렸습니다. 예외도 빈 값도 없이
 * <b>조용히 틀린 수익률</b>이 나오는 종류의 버그라 테스트로 막습니다.
 */
class SectorSeriesAlignmentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("종가가 비면 그 날짜도 함께 버린다")
    void dropsTheWholeSampleWhenEitherSideIsMissing() throws Exception {
        // 가운데 종가 하나가 null입니다. 날짜는 4개, 쓸 수 있는 종가는 3개.
        var series = MAPPER.readTree("""
                {"dates": ["2026-01-02", "2026-01-03", "2026-01-06", "2026-01-07"],
                 "close": [100.0, null, 110.0, 121.0]}
                """);

        SectorService service = new SectorService(null);
        Method aligned = SectorService.class
                .getDeclaredMethod("alignedSeries", tools.jackson.databind.JsonNode.class,
                        String.class);
        aligned.setAccessible(true);
        Object result = aligned.invoke(service, series, "close");

        Method datesOf = result.getClass().getDeclaredMethod("dates");
        Method closesOf = result.getClass().getDeclaredMethod("closes");
        datesOf.setAccessible(true);
        closesOf.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<LocalDate> dates = (List<LocalDate>) datesOf.invoke(result);
        @SuppressWarnings("unchecked")
        List<Double> closes = (List<Double>) closesOf.invoke(result);

        assertThat(dates).hasSameSizeAs(closes);
        assertThat(dates).containsExactly(
                LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 6), LocalDate.of(2026, 1, 7));
        assertThat(closes).containsExactly(100.0, 110.0, 121.0);

        // 2 거래일 전(100) 대비 마지막(121) = +21%.
        // 예전처럼 날짜만 4개로 남았다면 구간이 한 칸 밀려 다른 값이 나왔습니다.
        assertThat(SeriesMath.periodReturn(closes, 2)).isCloseTo(21.0, within(1e-9));
    }

    @Test
    @DisplayName("두 배열 길이가 달라도 짧은 쪽까지만 읽는다")
    void stopsAtTheShorterArray() throws Exception {
        var series = MAPPER.readTree("""
                {"dates": ["2026-01-02", "2026-01-03"], "close": [100.0, 101.0, 102.0]}
                """);

        SectorService service = new SectorService(null);
        Method aligned = SectorService.class
                .getDeclaredMethod("alignedSeries", tools.jackson.databind.JsonNode.class,
                        String.class);
        aligned.setAccessible(true);
        Object result = aligned.invoke(service, series, "close");

        Method closesOf = result.getClass().getDeclaredMethod("closes");
        closesOf.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Double> closes = (List<Double>) closesOf.invoke(result);

        assertThat(closes).containsExactly(100.0, 101.0);
    }
}
