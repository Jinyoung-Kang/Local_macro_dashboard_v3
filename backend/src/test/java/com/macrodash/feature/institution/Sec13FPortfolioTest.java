package com.macrodash.feature.institution;

import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Arrays;
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
 * 기관 1곳의 포트폴리오 화면(분기 대비 액션 · 비중 추이) — 저장본은 목으로 줍니다.
 */
class Sec13FPortfolioTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NPS = "0001608046";

    private static Sec13FService service(String cik, String quartersJson) {
        StoreReader store = mock(StoreReader.class);
        when(store.read(anyString(), anyLong(), anyString())).thenReturn(Optional.empty());
        Snapshot snapshot = new Snapshot(Datasets.sec13f(cik, Datasets.MAX_TRACKED_QUARTERS),
                MAPPER.readTree("{\"cik\":\"" + cik + "\",\"quarters\":" + quartersJson + "}"),
                "json", "ok", null, Instant.now());
        when(store.read(eq(Datasets.sec13f(cik, Datasets.MAX_TRACKED_QUARTERS)), anyLong(), anyString()))
                .thenReturn(Optional.of(snapshot));
        return new Sec13FService(store);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Double>> weightSeries(Map<String, Object> portfolio) {
        return (Map<String, List<Double>>) ((Map<String, Object>) portfolio.get("weightHistory")).get("series");
    }

    // ------------------------------------------------------------ QA-084 비중 추이는 회사(이름) 단위 합
    @Test
    @Disabled("QA-084: 수정 전 재현 — fix 커밋에서 켬")
    @DisplayName("QA-084 비중 추이·히트맵은 이름이 같은 클래스(A·C주)의 비중을 분기마다 합친다 — 한 칸을 버리지 않는다")
    void weightHistorySumsShareClassesOfOneName() {
        Sec13FService service = service(NPS, """
                [{"reportDate":"2026-06-30","holdings":[
                    {"name":"BIG CO","cusip":"111111111","class":"COM","value":1200,"shares":12,"weight":12.0},
                    {"name":"ALPHABET INC","cusip":"02079K305","class":"CAP STK CL A","value":600,"shares":6,"weight":6.0},
                    {"name":"ALPHABET INC","cusip":"02079K107","class":"CAP STK CL C","value":400,"shares":4,"weight":4.0},
                    {"name":"SMALL CO","cusip":"222222222","class":"COM","value":100,"shares":1,"weight":1.0}]},
                 {"reportDate":"2026-03-31","holdings":[
                    {"name":"BIG CO","cusip":"111111111","class":"COM","value":1100,"shares":12,"weight":11.0},
                    {"name":"ALPHABET INC","cusip":"02079K305","class":"CAP STK CL A","value":500,"shares":6,"weight":5.0},
                    {"name":"ALPHABET INC","cusip":"02079K107","class":"CAP STK CL C","value":300,"shares":4,"weight":3.0},
                    {"name":"SMALL CO","cusip":"222222222","class":"COM","value":150,"shares":1,"weight":1.5}]}]
                """);

        Map<String, List<Double>> series = weightSeries(service.portfolio(NPS, 8, 3));

        assertThat(series.keySet()).as("상위 3개 회사 — ALPHABET이 두 칸을 차지해 SMALL CO가 밀려나면 안 됨")
                .containsExactly("BIG CO", "ALPHABET INC", "SMALL CO");
        assertThat(series.get("ALPHABET INC")).as("과거 → 최신, A주 + C주").containsExactly(8.0, 10.0);
        assertThat(series.get("BIG CO")).containsExactly(11.0, 12.0);
    }

    @Test
    @Disabled("QA-084: 수정 전 재현 — fix 커밋에서 켬")
    @DisplayName("QA-084 들고 있는데 비중을 모르면 0이 아니라 null — 들고 있지 않은 분기만 0")
    void unknownWeightIsNullNotZero() {
        Sec13FService service = service(NPS, """
                [{"reportDate":"2026-06-30","holdings":[
                    {"name":"BIG CO","cusip":"111111111","class":"COM","value":1200,"shares":12,"weight":12.0},
                    {"name":"MYSTERY CO","cusip":"333333333","class":"COM","shares":5}]},
                 {"reportDate":"2026-03-31","holdings":[
                    {"name":"BIG CO","cusip":"111111111","class":"COM","value":1100,"shares":12,"weight":11.0}]}]
                """);

        Map<String, List<Double>> series = weightSeries(service.portfolio(NPS, 8, 10));

        assertThat(series.get("MYSTERY CO")).as("2026-03-31 미보유 → 0, 2026-06-30 보유·비중 모름 → null")
                .isEqualTo(Arrays.asList(0.0, null));
    }
}
