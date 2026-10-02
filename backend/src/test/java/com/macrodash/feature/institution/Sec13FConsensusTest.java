package com.macrodash.feature.institution;

import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
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
 * 13F 교집합 집계가 모르는 값을 0으로 더하지 않는지.
 */
class Sec13FConsensusTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NPS = "0001608046";
    private static final String NORGES = "0001374170";

    private static Snapshot snapshot(String cik, String quartersJson) {
        return new Snapshot(Datasets.sec13f(cik, Datasets.MAX_TRACKED_QUARTERS),
                MAPPER.readTree("{\"cik\":\"" + cik + "\",\"quarters\":" + quartersJson + "}"),
                "json", "ok", null, Instant.now());
    }

    private static Sec13FService service(Map<String, String> quartersByCik) {
        StoreReader store = mock(StoreReader.class);
        when(store.read(anyString(), anyLong(), anyString())).thenReturn(Optional.empty());
        quartersByCik.forEach((cik, json) ->
                when(store.read(eq(Datasets.sec13f(cik, Datasets.MAX_TRACKED_QUARTERS)), anyLong(), anyString()))
                        .thenReturn(Optional.of(snapshot(cik, json))));
        return new Sec13FService(store);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("평가액·비중을 모르는 기관은 합계와 평균에서 빠진다 — 0으로 더하지 않는다")
    void unknownValuesAreLeftOutOfSumsAndAverages() {
        // 국민연금: APPLE 평가액 1000·비중 10. 노르웨이: APPLE 평가액 모름·비중 모름(공시에 빠짐).
        Sec13FService service = service(Map.of(
                NPS, """
                    [{"reportDate":"2026-06-30","holdings":[
                        {"name":"APPLE INC","cusip":"037833100","class":"COM","value":1000,"shares":10,"weight":10.0}]},
                     {"reportDate":"2026-03-31","holdings":[
                        {"name":"APPLE INC","cusip":"037833100","class":"COM","value":900,"shares":10,"weight":9.0}]}]
                    """,
                NORGES, """
                    [{"reportDate":"2026-06-30","holdings":[
                        {"name":"APPLE INC","cusip":"037833100","class":"COM","shares":5}]},
                     {"reportDate":"2026-03-31","holdings":[
                        {"name":"APPLE INC","cusip":"037833100","class":"COM","shares":5}]}]
                    """));

        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) service.consensus(List.of(NPS, NORGES), null, 2, 10).get("rows");

        assertThat(rows).hasSize(1);
        Map<String, Object> apple = rows.get(0);
        assertThat(apple.get("holderCount")).isEqualTo(2);
        assertThat(apple.get("totalValue")).as("아는 평가액만").isEqualTo(1000.0);
        assertThat(apple.get("avgWeight")).as("비중을 아는 1곳 평균 — 2로 나누면 5%가 됨").isEqualTo(10.0);
        assertThat(apple.get("maxWeight")).isEqualTo(10.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("아무도 평가액을 모르면 합계는 0이 아니라 null이다")
    void totallyUnknownValueIsNull() {
        Sec13FService service = service(Map.of(
                NPS, """
                    [{"reportDate":"2026-06-30","holdings":[{"name":"X CORP","cusip":"1","class":"COM","shares":1}]},
                     {"reportDate":"2026-03-31","holdings":[{"name":"X CORP","cusip":"1","class":"COM","shares":1}]}]
                    """));

        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) service.consensus(List.of(NPS), null, 1, 10).get("rows");

        assertThat(rows.get(0).get("totalValue")).isNull();
        assertThat(rows.get(0).get("avgWeight")).isNull();
    }

    private static final String ALPHABET_TWO_CLASSES = """
        [{"reportDate":"2026-06-30","holdings":[
            {"name":"ALPHABET INC","cusip":"02079K305","class":"CL A","value":600,"shares":10,"weight":6.0},
            {"name":"ALPHABET INC","cusip":"02079K107","class":"CL C","value":400,"shares":10,"weight":4.0}]},
         {"reportDate":"2026-03-31","holdings":[
            {"name":"ALPHABET INC","cusip":"02079K305","class":"CL A","value":500,"shares":10,"weight":5.0},
            {"name":"ALPHABET INC","cusip":"02079K107","class":"CL C","value":300,"shares":10,"weight":3.0}]}]
        """;

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("한 기관이 같은 회사를 두 클래스(A·C주)로 들어도 기관 수·매수 기관 수는 1이고 비중은 기관 단위 합이다")
    void sameInstitutionHoldingTwoClassesCountsOnce() {
        Sec13FService service = service(Map.of(NPS, ALPHABET_TWO_CLASSES));

        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) service.consensus(List.of(NPS), null, 1, 10).get("rows");

        assertThat(rows).hasSize(1);
        Map<String, Object> alphabet = rows.get(0);
        assertThat(alphabet.get("holderCount")).as("기관 1곳 — 항목 수(2)가 아니다").isEqualTo(1);
        assertThat((List<String>) alphabet.get("holders")).containsExactly(Institutions.byCik(NPS).get("name"));
        assertThat(alphabet.get("buyCount")).as("두 클래스 모두 비중 확대지만 기관은 1곳").isEqualTo(1L);
        assertThat(alphabet.get("totalValue")).isEqualTo(1000.0);
        assertThat(alphabet.get("avgWeight")).as("기관의 ALPHABET 비중 = 6 + 4").isEqualTo(10.0);
        assertThat(alphabet.get("maxWeight")).isEqualTo(10.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("공통 신규 매수도 기관 단위로 센다")
    void newBuysCountInstitutionsNotHoldings() {
        Sec13FService service = service(Map.of(NPS, """
            [{"reportDate":"2026-06-30","holdings":[
                {"name":"ALPHABET INC","cusip":"02079K305","class":"CL A","value":600,"shares":10,"weight":6.0},
                {"name":"ALPHABET INC","cusip":"02079K107","class":"CL C","value":400,"shares":10,"weight":4.0}]},
             {"reportDate":"2026-03-31","holdings":[
                {"name":"APPLE INC","cusip":"037833100","class":"COM","value":900,"shares":10,"weight":9.0}]}]
            """));

        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) service.newBuys(List.of(NPS), null, 1).get("rows");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("buyerCount")).isEqualTo(1);
        assertThat((List<String>) rows.get(0).get("buyers")).containsExactly(Institutions.byCik(NPS).get("name"));
        assertThat(rows.get(0).get("totalValue")).isEqualTo(1000.0);
        assertThat(rows.get(0).get("avgWeight")).isEqualTo(10.0);
    }
}
