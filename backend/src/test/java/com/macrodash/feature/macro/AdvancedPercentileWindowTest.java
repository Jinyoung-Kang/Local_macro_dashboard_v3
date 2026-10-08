package com.macrodash.feature.macro;

import com.macrodash.collector.CollectorClient;
import com.macrodash.config.AppProperties;
import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 심화 지표의 "표본 백분위"는 표본 기간을 함께 내려보내야 합니다.
 *
 * <p>IG 스프레드 카드가 "과열"(절대 기준 1.0% 미만)인데 "표본 백분위 46.7%"로 보여, 두 값이 서로
 * 어긋나 보였습니다. 판정은 맞습니다. 문제는 백분위의 표본입니다 — ICE BofA 시리즈(BAMLC0A0CM·
 * BAMLH0A0HYM2)는 FRED가 최근 3년만 제공해 백분위가 "최근 3년 안에서"인데, 다른 지표는 약 10년이고
 * 화면은 그 차이를 알리지 않았습니다. {@code SeriesMath.percentile}의 설명도 "화면은 표본 수를 함께
 * 보여 줘야 이 값을 제대로 읽을 수 있습니다"라고 적고 있습니다.
 */
class AdvancedPercentileWindowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static MacroService serviceWithIgSeries() throws Exception {
        StoreReader store = mock(StoreReader.class);
        when(store.readMode()).thenReturn(AppProperties.ReadMode.STORE_ONLY);
        when(store.read(anyString(), anyLong(), anyString())).thenReturn(Optional.empty());
        // FRED가 3년만 주는 시리즈 — 첫 점이 2023-10-09.
        when(store.read(eq(Datasets.fredSeries("BAMLC0A0CM")), anyLong(), anyString()))
                .thenReturn(Optional.of(new Snapshot(Datasets.fredSeries("BAMLC0A0CM"), MAPPER.readTree("""
                        {"points":[{"date":"2023-10-09","value":1.31},{"date":"2024-10-09","value":0.92},
                                   {"date":"2026-10-07","value":0.80}]}
                        """), "json", "ok", null, Instant.now())));
        return new MacroService(store, mock(CollectorClient.class));
    }

    @Test
    @DisplayName("백분위와 함께 표본 시작일·개수·기간(년)을 내려보낸다")
    void percentileComesWithItsSampleWindow() throws Exception {
        Map<String, Object> advanced = serviceWithIgSeries().advancedIndicators();

        @SuppressWarnings("unchecked")
        Map<String, Object> ig = (Map<String, Object>) ((Map<String, Object>) advanced.get("latest")).get("BAMLC0A0CM");

        assertThat((Double) ig.get("percentile")).as("최신값 0.80이 표본 3개 중 가장 낮음").isCloseTo(33.33, within(0.01));
        assertThat(ig.get("percentileFrom")).isEqualTo("2023-10-09");
        assertThat(ig.get("percentileCount")).isEqualTo(3);
        assertThat(ig.get("percentileYears")).as("2023-10-09 ~ 2026-10-07").isEqualTo(3.0);
    }

    @Test
    @DisplayName("IG 스프레드 출처에 'FRED가 최근 3년만 제공'을 적는다")
    void igSourceDisclosesThreeYearLimit() throws Exception {
        Map<String, Object> advanced = serviceWithIgSeries().advancedIndicators();

        @SuppressWarnings("unchecked")
        Map<String, Object> ig = (Map<String, Object>) ((Map<String, Object>) advanced.get("latest")).get("BAMLC0A0CM");

        assertThat(String.valueOf(ig.get("source"))).contains("최근 3년");
    }
}
