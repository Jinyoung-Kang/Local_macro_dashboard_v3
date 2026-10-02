package com.macrodash.feature.macro;

import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiquidityServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 거래일마다 한 행, 값은 행 번호(0,1,2,…) — 날짜로 세는지 행으로 세는지가 바로 드러납니다. */
    private static JsonNode dailyRows(int tradingDays) {
        StringBuilder rows = new StringBuilder();
        LocalDate day = LocalDate.of(2026, 1, 5);           // 월요일
        for (int i = 0; i < tradingDays; i++) {
            while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                day = day.plusDays(1);
            }
            if (i > 0) {
                rows.append(',');
            }
            rows.append("{\"date\":\"").append(day).append("\",\"netLiquidityT\":").append(i).append('}');
            day = day.plusDays(1);
        }
        return MAPPER.readTree("{\"isEstimated\":false,\"rows\":[" + rows + "]}");
    }

    private static Map<String, Object> netLiquidity(JsonNode payload) {
        StoreReader store = mock(StoreReader.class);
        when(store.read(anyString(), anyLong(), anyString())).thenReturn(Optional.empty());
        when(store.read(eq(Datasets.SNAP_FED_LIQUIDITY), anyLong(), anyString()))
                .thenReturn(Optional.of(new Snapshot(
                        Datasets.SNAP_FED_LIQUIDITY, payload, "json", "ok", null, Instant.now())));
        return new LiquidityService(store).netLiquidity(null);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("4주·12주 변화는 행 수가 아니라 날짜로 센다")
    void momentumIsMeasuredInWeeksNotRows() {
        // 65거래일 = 13주(마지막 행 2026-04-03 금). 값이 행 번호이므로 4주 변화 = 거래일 20일 = 20,
        // 12주 변화 = 거래일 60일 = 60. 예전 코드는 "4행 전"을 써서 4·12가 나왔습니다.
        Map<String, Object> momentum = (Map<String, Object>) netLiquidity(dailyRows(65)).get("momentum");

        assertThat(momentum.get("change4w")).as("4주 = 거래일 20일").isEqualTo(20.0);
        assertThat(momentum.get("change12w")).as("12주 = 거래일 60일").isEqualTo(60.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("4주 전 값이 없으면 변화도 없다 — 짧은 구간을 4주라고 부르지 않는다")
    void missingBaselineGivesNull() {
        Map<String, Object> momentum = (Map<String, Object>) netLiquidity(dailyRows(10)).get("momentum");

        assertThat(momentum.get("change4w")).isNull();
        assertThat(momentum.get("change12w")).isNull();
    }
}
