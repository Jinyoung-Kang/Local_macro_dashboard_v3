package com.macrodash.feature.institution;

import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import org.junit.jupiter.api.Disabled;
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

    // ------------------------------------------------------------ QA-082 전량 매도는 '보유'가 아니다
    private static final String SCION = "0001649339";
    private static final String VANGUARD = "0000102909";

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("rows");
    }

    private static Map<String, Object> rowNamed(List<Map<String, Object>> rows, String name) {
        return rows.stream().filter(row -> name.equals(row.get("name"))).findFirst().orElseThrow();
    }

    @Test
    @Disabled("QA-082: 수정 전 재현 — fix 커밋에서 켬")
    @SuppressWarnings("unchecked")
    @DisplayName("QA-082 이번 분기에 전량 매도한 기관은 보유 기관이 아니다 — 보유 기관 수·평균 비중·평가액에서 빠지고 '동시 매도'에만 남는다")
    void closedPositionIsNotAHolding() {
        // 국민연금은 X를 5% 들고 있고, 사이언은 이번 분기에 X를 전량 매도했습니다(지금은 Y만 보유).
        Sec13FService service = service(Map.of(
                NPS, """
                    [{"reportDate":"2026-06-30","holdings":[
                        {"name":"X CORP","cusip":"111111111","class":"COM","value":500,"shares":10,"weight":5.0}]},
                     {"reportDate":"2026-03-31","holdings":[
                        {"name":"X CORP","cusip":"111111111","class":"COM","value":500,"shares":10,"weight":5.0}]}]
                    """,
                SCION, """
                    [{"reportDate":"2026-06-30","holdings":[
                        {"name":"Y CORP","cusip":"222222222","class":"COM","value":100,"shares":1,"weight":100.0}]},
                     {"reportDate":"2026-03-31","holdings":[
                        {"name":"X CORP","cusip":"111111111","class":"COM","value":300,"shares":3,"weight":100.0}]}]
                    """));

        assertThat(rows(service.consensus(List.of(NPS, SCION), null, 2, 10)))
                .as("X를 지금 들고 있는 기관은 국민연금 1곳 — '보유 기관 2곳'으로 올리면 안 됨")
                .isEmpty();

        Map<String, Object> x = rowNamed(rows(service.consensus(List.of(NPS, SCION), null, 1, 10)), "X CORP");
        assertThat(x.get("holderCount")).isEqualTo(1);
        assertThat((List<String>) x.get("holders")).containsExactly(Institutions.byCik(NPS).get("name"));
        assertThat(x.get("avgWeight")).as("전량 매도의 0%를 평균에 넣지 않음(넣으면 2.5)").isEqualTo(5.0);
        assertThat(x.get("maxWeight")).isEqualTo(5.0);
        assertThat(x.get("totalValue")).isEqualTo(500.0);
        assertThat(x.get("sellCount")).as("'동시 매도'에는 그대로 남는다").isEqualTo(1L);
    }

    @Test
    @Disabled("QA-082: 수정 전 재현 — fix 커밋에서 켬")
    @DisplayName("QA-082 전량 매도는 보유 종목 수와 상관없이 같은 규칙으로 '동시 매도'에 센다 — 상위 100 자르기는 지금 보유한 항목에만")
    void closedPositionCountsAsSellerRegardlessOfPortfolioSize() {
        // 뱅가드는 지금 120종목을 들고 있고, 이번 분기에 X를 전량 매도했습니다. 예전에는 평가액 0인 전량 매도 행이
        // 상위 100 밖으로 밀려, 보유 종목이 100개 미만인 기관의 전량 매도만 '동시 매도'로 셌습니다.
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            current.append(i == 0 ? "" : ",").append("""
                    {"name":"FILLER %d","cusip":"9%08d","class":"COM","value":%d,"shares":1,"weight":0.5}"""
                    .formatted(i, i, 1000 - i));
        }
        Sec13FService service = service(Map.of(
                NPS, """
                    [{"reportDate":"2026-06-30","holdings":[
                        {"name":"X CORP","cusip":"111111111","class":"COM","value":500,"shares":10,"weight":5.0}]},
                     {"reportDate":"2026-03-31","holdings":[
                        {"name":"X CORP","cusip":"111111111","class":"COM","value":500,"shares":10,"weight":5.0}]}]
                    """,
                VANGUARD, """
                    [{"reportDate":"2026-06-30","holdings":[%s]},
                     {"reportDate":"2026-03-31","holdings":[
                        {"name":"X CORP","cusip":"111111111","class":"COM","value":300,"shares":3,"weight":1.0}]}]
                    """.formatted(current)));

        Map<String, Object> x = rowNamed(rows(service.consensus(List.of(NPS, VANGUARD), null, 1, 200)), "X CORP");
        assertThat(x.get("holderCount")).isEqualTo(1);
        assertThat(x.get("sellCount")).as("뱅가드의 전량 매도도 '동시 매도'").isEqualTo(1L);
    }
}
