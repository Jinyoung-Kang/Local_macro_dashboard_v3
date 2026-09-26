package com.macrodash.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class InvestorFlowsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 기록 하나. null을 넣으면 그 투자자는 "모름"(당일 잠정치 모양)입니다. */
    private static JsonNode record(String date, Long foreigner, Long institution, Long individual,
                                   Long pension, Double holdingRate) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("date", date);
        node.put("updatedAt", date + "T18:10:00+09:00");
        ObjectNode investors = node.putObject("investors");
        putNet(investors, "foreigner", foreigner);
        putNet(investors, "institution", institution);
        putNet(investors, "individual", individual);
        investors.putNull("otherCorporation");
        if (pension == null) {
            node.putNull("breakdown");
        } else {
            ObjectNode breakdown = node.putObject("breakdown");
            putNet(breakdown, "pensionFund", pension);
            putNet(breakdown, "financialInvestment", -pension);
        }
        if (holdingRate == null) {
            node.putNull("foreignerHoldingRate");
        } else {
            node.put("foreignerHoldingRate", holdingRate);
        }
        return node;
    }

    private static void putNet(ObjectNode parent, String key, Long net) {
        if (net == null) {
            parent.putNull(key);
        } else {
            parent.putObject(key).put("net", net);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> investor(Map<String, Object> summary, String key) {
        return ((List<Map<String, Object>>) summary.get("investors")).stream()
                .filter(row -> key.equals(row.get("key"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("누적은 값이 있는 날만 더하고, 더한 일수를 함께 돌려준다 (null을 0으로 더하지 않음)")
    void sumsSkipNullsAndReportDays() {
        List<JsonNode> records = List.of(
                record("2026-09-25", 300L, 50L, null, null, null),   // 당일 잠정: 개인·기관 세부 없음
                record("2026-09-24", 200L, -10L, -150L, 40L, 0.5089),
                record("2026-09-23", -100L, 20L, 80L, 10L, 0.5085));

        Map<String, Object> summary = InvestorFlows.summarize(records, LocalDate.parse("2026-09-25"), 0);

        Map<String, Object> foreigner = investor(summary, "foreigner");
        assertThat(foreigner.get("latest")).isEqualTo(300L);
        assertThat(foreigner.get("net5")).isEqualTo(Map.of("sum", 400L, "days", 3, "window", 5));

        Map<String, Object> individual = investor(summary, "individual");
        assertThat(individual.get("latest")).isNull();           // "0"이 아니라 "모름"
        assertThat(individual.get("net5")).isEqualTo(Map.of("sum", -70L, "days", 2, "window", 5));

        Map<String, Object> other = investor(summary, "otherCorporation");
        assertThat(((Map<String, Object>) other.get("net20")).get("sum")).isNull();

        assertThat(summary.get("provisional")).isEqualTo(true);
    }

    @Test
    @DisplayName("연속 일수: 부호가 이어진 만큼, 모르는 날에서 멈춘다")
    void streakStopsAtSignChangeOrNull() {
        List<JsonNode> buying = List.of(
                record("d5", 10L, -1L, 1L, null, null),
                record("d4", 20L, -2L, null, null, null),
                record("d3", 5L, 3L, 1L, null, null),
                record("d2", -1L, -4L, 1L, null, null));
        assertThat(InvestorFlows.streak(buying, "foreigner")).isEqualTo(3);
        assertThat(InvestorFlows.streak(buying, "institution")).isEqualTo(-2);
        assertThat(InvestorFlows.streak(buying, "individual")).isEqualTo(1);   // 다음 날이 null → 멈춤
        assertThat(InvestorFlows.streak(List.of(record("d", 0L, null, null, null, null)), "foreigner"))
                .isZero();
        assertThat(InvestorFlows.streak(List.of(), "foreigner")).isZero();
    }

    @Test
    @DisplayName("외국인 보유율: 최신 값과 창 안 변화(%p), 값이 한 날뿐이면 변화 없음")
    void foreignerHoldingChange() {
        List<JsonNode> records = List.of(
                record("2026-09-25", 1L, 1L, 1L, null, null),
                record("2026-09-24", 1L, 1L, 1L, null, 0.5089),
                record("2026-09-23", 1L, 1L, 1L, null, 0.5085));
        Map<String, Object> holding = InvestorFlows.foreignerHolding(records, 20);
        assertThat((Double) holding.get("ratePct")).isCloseTo(50.89, within(1e-9));
        assertThat((Double) holding.get("changePp")).isCloseTo(0.04, within(1e-9));
        assertThat(holding.get("date")).isEqualTo("2026-09-24");
        assertThat(holding.get("fromDate")).isEqualTo("2026-09-23");

        Map<String, Object> single = InvestorFlows.foreignerHolding(records.subList(0, 2), 20);
        assertThat(single.get("changePp")).isNull();
        assertThat(InvestorFlows.foreignerHolding(records.subList(0, 1), 20)).isNull();
    }

    @Test
    @DisplayName("차트 계열은 오래된 날이 앞이다")
    void seriesAscending() {
        List<JsonNode> records = new ArrayList<>();
        for (int day = 25; day >= 1; day--) {
            records.add(record(String.format("2026-09-%02d", day), (long) day, 0L, 0L, null, null));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>)
                InvestorFlows.summarize(records, LocalDate.parse("2026-09-26"), 20).get("series");
        assertThat(series).hasSize(20);
        assertThat(series.get(0).get("date")).isEqualTo("2026-09-06");
        assertThat(series.get(19).get("date")).isEqualTo("2026-09-25");
    }

    @Test
    @DisplayName("현물·선물 동조: 방향만 비교하고, 기준일이 다르면 당일 판정을 하지 않는다")
    void spotFuturesVerdicts() {
        List<JsonNode> spot = List.of(
                record("2026-09-25", 2000L, -500L, -1500L, 300L, null),
                record("2026-09-24", 1000L, -500L, -500L, 100L, null));
        List<JsonNode> futures = List.of(
                MAPPER.createObjectNode().put("investor", "외국인 (스마트머니)")
                        .put("netToday", 1200).put("net5d", 3000).put("net20d", -800),
                MAPPER.createObjectNode().put("investor", "기관계")
                        .put("netToday", -300).put("net5d", -900).put("net20d", -100),
                MAPPER.createObjectNode().put("investor", "금융투자 (차익거래)")
                        .put("netToday", 400).put("net5d", 700).put("net20d", 900));

        Map<String, Object> same = InvestorFlows.spotFutures(spot, futures, "2026-09-25");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) same.get("rows");
        Map<String, Object> foreigner = rows.get(0);
        assertThat(foreigner.get("verdictToday")).isEqualTo("동반 순매수");
        assertThat(foreigner.get("verdict5")).isEqualTo("동반 순매수");
        assertThat(foreigner.get("verdict20")).isEqualTo("현물 매수 · 선물 매도");
        assertThat(rows.get(1).get("verdict5")).isEqualTo("동반 순매도");
        // 개인은 Daum 행이 없으면 판단 불가 (0으로 채우지 않음)
        assertThat(rows.get(2).get("verdict5")).isEqualTo("판단 불가");
        // 금융투자는 기관 세부에서 꺼냅니다: 현물 −400(= −300 −100), 선물 +700
        Map<String, Object> financial = rows.get(3);
        assertThat(financial.get("spot5")).isEqualTo(-400L);
        assertThat(financial.get("verdict5")).isEqualTo("현물 매도 · 선물 매수");

        // 예전 저장본처럼 설명 없는 이름("외국인")이어도 같은 행으로 맞춥니다
        List<JsonNode> legacy = List.of(MAPPER.createObjectNode().put("investor", "외국인")
                .put("netToday", 1).put("net5d", 1).put("net20d", 1));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legacyRows = (List<Map<String, Object>>)
                InvestorFlows.spotFutures(spot, legacy, "2026-09-25").get("rows");
        assertThat(legacyRows.get(0).get("verdict5")).isEqualTo("동반 순매수");
        assertThat(InvestorFlows.firstWord("금융투자 (차익거래)")).isEqualTo("금융투자");
        assertThat(InvestorFlows.firstWord("외국인(스마트머니)")).isEqualTo("외국인");

        Map<String, Object> shifted = InvestorFlows.spotFutures(spot, futures, "2026-09-24");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shiftedRows = (List<Map<String, Object>>) shifted.get("rows");
        assertThat(shifted.get("sameDay")).isEqualTo(false);
        assertThat(shiftedRows.get(0).get("verdictToday")).isEqualTo("기준일 다름");
    }
}
