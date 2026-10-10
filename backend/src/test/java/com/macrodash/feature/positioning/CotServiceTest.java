package com.macrodash.feature.positioning;

import com.macrodash.read.StoreReader;
import com.macrodash.support.InvalidRequestException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CotServiceTest {

    @Test
    @DisplayName("lookbackWeeks는 8~520주로 접는다 — Integer.MAX_VALUE가 lookback+8을 넘치게 하지 않는다")
    void lookbackIsClamped() {
        assertThat(CotService.effectiveLookback(Integer.MAX_VALUE)).isEqualTo(CotService.MAX_LOOKBACK_WEEKS);
        assertThat(CotService.effectiveLookback(0)).isEqualTo(52);     // 기본값
        assertThat(CotService.effectiveLookback(-5)).isEqualTo(52);
        assertThat(CotService.effectiveLookback(3)).isEqualTo(8);
        assertThat(CotService.effectiveLookback(104)).isEqualTo(104);
        assertThat(CotService.effectiveLookback(104) + 8).isPositive();
    }

    @Test
    @DisplayName("percentile이 NaN이면 400 — 조용히 이벤트 0건으로 끝내지 않는다")
    void nanPercentileIsRejected() {
        CotService service = new CotService(mock(StoreReader.class));

        assertThatThrownBy(() -> service.extremes("Gold", Double.NaN, 52))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.extremes("Gold", Double.POSITIVE_INFINITY, 52))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("QA-061: 최신 주 순포지션을 모르면 백분위도 모름 — 직전 주의 백분위를 최신 날짜 옆에 찍지 않는다")
    void percentileIsUnknownWhenLatestIsUnknown() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> rows = new ArrayList<>();
        for (int week = 1; week <= 10; week++) {
            rows.add(mapper.readTree("{\"date\": \"2026-08-%02d\", \"ncNet\": %d}".formatted(week, week * 100)));
        }
        rows.add(mapper.readTree("{\"date\": \"2026-08-20\", \"ncNet\": null}"));

        Map<String, Object> summary = new CotService(mock(StoreReader.class)).summarize("Gold", rows);

        assertThat(summary.get("ncNet")).isNull();
        assertThat(summary.get("percentile")).isNull();
    }
}
