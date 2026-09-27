package com.macrodash.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 토스 투자자별 매매대금 정합성 판정.
 *
 * <p>핵심 성질: 빈 값(잠정치)을 0으로 메워 등식을 "통과"시키지 않고, 등식이 깨지면
 * 불일치로 알린다.
 */
class FlowIntegrityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 네 분류 + 기관 세부. buys/sells 순서: 개인, 외국인, 기관, 기타법인. */
    private static ObjectNode record(String date, Long[] buys, Long[] sells, boolean withBreakdown) {
        ObjectNode record = MAPPER.createObjectNode();
        record.put("date", date);
        ObjectNode investors = record.putObject("investors");
        for (int i = 0; i < FlowIntegrity.INVESTORS.size(); i++) {
            ObjectNode side = investors.putObject(FlowIntegrity.INVESTORS.get(i));
            putOrNull(side, "buy", buys[i]);
            putOrNull(side, "sell", sells[i]);
        }
        if (withBreakdown) {
            // 기관(인덱스 2)을 세부 7개에 나눠 담습니다: 첫 항목에 나머지를 몰아 줍니다.
            ObjectNode breakdown = record.putObject("breakdown");
            int n = FlowIntegrity.BREAKDOWN.size();
            for (int i = 0; i < n; i++) {
                ObjectNode side = breakdown.putObject(FlowIntegrity.BREAKDOWN.get(i));
                side.put("buy", i == 0 ? buys[2] - (n - 1) : 1);
                side.put("sell", i == 0 ? sells[2] - (n - 1) : 1);
            }
        }
        return record;
    }

    private static void putOrNull(ObjectNode node, String field, Long value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static Map<String, List<JsonNode>> kospi(JsonNode... records) {
        Map<String, List<JsonNode>> out = new LinkedHashMap<>();
        out.put("KOSPI", List.of(records));
        return out;
    }

    @Test
    @DisplayName("매수 합계 = 매도 합계, 기관 = 세부 합이면 일치")
    void balancedRecordMatches() {
        JsonNode rec = record("2026-09-23",
                new Long[]{500L, 300L, 200L, 100L}, new Long[]{450L, 350L, 180L, 120L}, true);

        Verification.Result result = FlowIntegrity.check(kospi(rec));

        assertThat(result.verdict()).isEqualTo(Verification.MATCH);
        assertThat(result.readings()).hasSize(2);
        assertThat(result.note()).contains("코스피 2026-09-23");
    }

    @Test
    @DisplayName("매수·매도 합계가 갈라지면 불일치 — 필드 매핑이 깨진 신호")
    void unbalancedRecordMismatches() {
        JsonNode rec = record("2026-09-23",
                new Long[]{500L, 300L, 200L, 100L}, new Long[]{450L, 350L, 180L, 20L}, false);

        Verification.Result result = FlowIntegrity.check(kospi(rec));

        assertThat(result.verdict()).isEqualTo(Verification.MISMATCH);
        assertThat(result.note()).contains("매수·매도 합계");
    }

    @Test
    @DisplayName("기관 합계가 세부 7개 합과 다르면 불일치")
    void institutionBreakdownMismatch() {
        ObjectNode rec = record("2026-09-23",
                new Long[]{500L, 300L, 200L, 100L}, new Long[]{450L, 350L, 180L, 120L}, true);
        ((ObjectNode) rec.path("breakdown").path("bank")).put("buy", 90L);

        Verification.Result result = FlowIntegrity.check(kospi(rec));

        assertThat(result.verdict()).isEqualTo(Verification.MISMATCH);
        assertThat(result.note()).contains("기관 합계와 세부");
    }

    @Test
    @DisplayName("잠정치(빈 값)는 건너뛰고 가장 최근 확정 기록으로 판정")
    void skipsProvisionalRecord() {
        JsonNode provisional = record("2026-09-24",
                new Long[]{null, 300L, 200L, null}, new Long[]{null, 350L, 180L, null}, false);
        JsonNode confirmed = record("2026-09-23",
                new Long[]{500L, 300L, 200L, 100L}, new Long[]{450L, 350L, 180L, 120L}, false);

        Verification.Result result = FlowIntegrity.check(kospi(provisional, confirmed));

        assertThat(result.verdict()).isEqualTo(Verification.MATCH);
        assertThat(result.readings().get(0).asOf()).isEqualTo("2026-09-23");
    }

    @Test
    @DisplayName("확정 기록이 없으면 '확인 못 함' — 빈 값을 0으로 메워 일치로 위장하지 않음")
    void noCompleteRecordIsSkipped() {
        JsonNode provisional = record("2026-09-24",
                new Long[]{null, 300L, 200L, null}, new Long[]{null, 350L, 180L, null}, false);

        Verification.Result result = FlowIntegrity.check(kospi(provisional));

        assertThat(result.verdict()).isEqualTo(Verification.SKIPPED);
    }
}
