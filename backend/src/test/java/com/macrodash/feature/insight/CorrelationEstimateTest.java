package com.macrodash.feature.insight;

import com.macrodash.Kst;
import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import org.junit.jupiter.api.Disabled;
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
 * 상관 분석 계열의 추정 표시 — KRX 수집이 실패하면 KOSPI200 선물 계열은 KODEX 200 종가로 환산한 추정치입니다.
 */
class CorrelationEstimateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** KRX 선물 저장본 40행과 같은 날짜의 SPY 종가. estimated면 수집기가 KODEX 200으로 대신 채운 모양입니다. */
    static AnalyticsService service(boolean estimated) {
        ObjectNode krx = MAPPER.createObjectNode().put("isEstimated", estimated);
        ArrayNode rows = krx.putArray("rows");
        ObjectNode sector = MAPPER.createObjectNode();
        ObjectNode spy = sector.putObject("tickers").putObject("SPY");
        ArrayNode dates = spy.putArray("dates");
        ArrayNode close = spy.putArray("close");
        int i = 0;
        for (LocalDate date = Kst.today().minusDays(60); i < 40; date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            ObjectNode row = rows.addObject().put("date", date.toString())
                    .put("futuresClose", 350.0 + i * 0.5 + 2.0 * Math.sin(i / 3.0));
            row.put("contractName", estimated ? "KOSPI 200 최근월물 (KODEX 200 기반 추정)" : "KOSPI 200 선물");
            dates.add(date.toString());
            close.add(500.0 + i * 0.7 + 3.0 * Math.cos(i / 4.0));
            i++;
        }

        StoreReader store = mock(StoreReader.class);
        when(store.read(anyString(), anyLong(), anyString())).thenReturn(Optional.empty());
        when(store.read(eq(Datasets.SNAP_KRX_FUTURES), anyLong(), anyString())).thenReturn(Optional.of(
                new Snapshot(Datasets.SNAP_KRX_FUTURES, krx, "json", estimated ? "estimated" : "ok", null, Instant.now())));
        when(store.read(eq(Datasets.SNAP_SECTOR_HISTORY), anyLong(), anyString())).thenReturn(Optional.of(
                new Snapshot(Datasets.SNAP_SECTOR_HISTORY, sector, "json", "ok", null, Instant.now())));
        return new AnalyticsService(store);
    }

    @Test
    @Disabled("QA-090: 수정 전 재현 — fix 커밋에서 켬")
    @DisplayName("QA-090 KOSPI200 선물 계열이 KODEX 200 추정치면 응답이 그 계열에 isEstimated·추정 출처·안내문을 싣는다")
    void estimatedKrxSeriesIsMarked() {
        JsonNode response = MAPPER.valueToTree(service(true).correlation("krx:futures", "etf:SPY", 20, 1, "change"));

        assertThat(response.path("available").asBoolean()).isTrue();
        assertThat(response.path("x").path("isEstimated").asBoolean(false)).as("x 계열은 추정치").isTrue();
        assertThat(response.path("x").path("source").asString()).as("공식 일별 마감이라고 적지 않음").contains("KODEX 200");
        assertThat(response.path("y").path("isEstimated").asBoolean(true)).as("SPY는 추정치가 아님").isFalse();
        assertThat(response.path("notes").toString()).contains("추정");
    }
}
