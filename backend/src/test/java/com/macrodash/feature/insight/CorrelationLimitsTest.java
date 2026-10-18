package com.macrodash.feature.insight;

import com.macrodash.Kst;
import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA-133: 상관 분석은 응답 캐시를 거치지 않는데 롤링 창·조회 기간에 상한이 없다.
 *
 * <p>롤링 상관은 (표본 수 × 창 크기)만큼 계산하고, 표본 수는 조회 기간 안의 저장본 길이입니다. FRED를 CSV로 받는
 * 배포는 수십 년을 저장하므로 URL로 창·기간을 크게 주면 화면이 고를 수 없는 크기의 계산을 요청마다 하게 됩니다.
 * 여기서는 일간 FRED 계열 두 개를 15년치로 두고, 화면이 고를 수 있는 최대(창 250 · 10년)를 넘는 값을 줍니다.
 */
class CorrelationLimitsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 오늘까지 15년치 평일 값. 두 계열의 주기를 달리해 변화끼리의 상관이 계산되게 합니다. */
    private static Optional<Snapshot> fifteenYears(String name, double period) {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode array = payload.putArray("points");
        int i = 0;
        for (LocalDate date = Kst.today().minusYears(15); !date.isAfter(Kst.today()); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            array.addObject().put("date", date.toString()).put("value", 3.0 + Math.sin(i / period) + i * 0.0001);
            i++;
        }
        return Optional.of(new Snapshot(name, payload, "json", "ok", null, Instant.now()));
    }

    @Test
    @DisplayName("QA-133 롤링 창은 250, 조회 기간은 10년으로 접고 응답 window는 접은 값을 돌려준다")
    void windowAndYearsAreFoldedToScreenMaximum() {
        StoreReader store = mock(StoreReader.class);
        when(store.read(anyString(), anyLong(), anyString())).thenReturn(Optional.empty());
        when(store.read(eq(Datasets.fredSeries("DGS10")), anyLong(), anyString()))
                .thenReturn(fifteenYears(Datasets.fredSeries("DGS10"), 7.0));
        when(store.read(eq(Datasets.fredSeries("DGS2")), anyLong(), anyString()))
                .thenReturn(fifteenYears(Datasets.fredSeries("DGS2"), 11.0));
        // 호출 전에 잽니다 — 호출 중에 날짜가 바뀌어도 서비스의 기준일은 이보다 늦을 뿐입니다.
        LocalDate tenYearsAgo = Kst.yearsAgo(10);

        JsonNode response = MAPPER.valueToTree(
                new AnalyticsService(store).correlation("fred:DGS10", "fred:DGS2", 100_000, 100, "change"));

        // 수정 전: window는 받은 값(100000) 그대로, 조회 기간은 100년이라 15년치 전부가 계산에 들어갔습니다.
        assertThat(response.path("available").asBoolean()).isTrue();
        assertThat(response.path("window").asInt()).as("응답 window는 접은 값").isEqualTo(250);
        assertThat(LocalDate.parse(response.path("firstDate").asString()))
                .as("조회 기간은 최근 10년까지").isAfterOrEqualTo(tenYearsAgo)
                // 반대쪽도 고정합니다 — 상한이 10년보다 줄면 화면의 '최근 10년'이 말없이 짧아집니다. 7일은 기준일 뒤 주말,
                // 변화 계열이 첫 날을 버리는 것, 호출 중 날짜가 바뀌는 것을 덮는 여유입니다.
                .as("화면의 최근 10년은 다 들어감").isBefore(tenYearsAgo.plusDays(7));
    }
}
