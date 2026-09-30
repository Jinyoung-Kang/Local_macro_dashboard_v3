package com.macrodash.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrodash.support.FlowJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 환율 재기준화와 수급 교집합의 계산 규칙.
 *
 * <p>둘 다 "없는 값을 만들지 않는다"가 핵심입니다. 재기준화는 기준값이 없으면
 * 빈 결과를, 교집합은 한쪽 금액이 없으면 합계를 null로 둡니다.
 */
class FxAndConsensusTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    @DisplayName("FxIndex")
    class FxIndexTest {

        @Test
        @DisplayName("첫 유효값을 100으로 놓는다")
        void rebasesToHundred() {
            NavigableMap<LocalDate, Double> series = new TreeMap<>();
            series.put(LocalDate.of(2026, 1, 2), 1300.0);
            series.put(LocalDate.of(2026, 1, 3), 1365.0);
            series.put(LocalDate.of(2026, 1, 6), 1274.0);

            FxIndex.Rebased rebased = FxIndex.rebase(series);

            assertThat(rebased.baseDate()).isEqualTo(LocalDate.of(2026, 1, 2));
            assertThat(rebased.baseValue()).isEqualTo(1300.0);
            assertThat(rebased.values().get(LocalDate.of(2026, 1, 2))).isEqualTo(100.0);
            assertThat(rebased.values().get(LocalDate.of(2026, 1, 3))).isEqualTo(105.0);
            assertThat(rebased.values().get(LocalDate.of(2026, 1, 6))).isEqualTo(98.0);
        }

        @Test
        @DisplayName("앞쪽 결측은 건너뛰고 첫 실제 값을 기준으로 삼는다")
        void skipsLeadingNulls() {
            NavigableMap<LocalDate, Double> series = new TreeMap<>();
            series.put(LocalDate.of(2026, 1, 1), null);
            series.put(LocalDate.of(2026, 1, 2), 150.0);
            series.put(LocalDate.of(2026, 1, 3), 165.0);

            FxIndex.Rebased rebased = FxIndex.rebase(series);

            assertThat(rebased.baseDate()).isEqualTo(LocalDate.of(2026, 1, 2));
            // 기준일 앞의 결측은 결측으로 남습니다 — 100으로 채우면 없던 관측이
            // 생기고, 차트는 그 자리에서 시작하는 선을 그립니다.
            assertThat(rebased.values().get(LocalDate.of(2026, 1, 1))).isNull();
            assertThat(rebased.values().get(LocalDate.of(2026, 1, 3)))
                    .isCloseTo(110.0, within(1e-9));
        }

        @Test
        @DisplayName("유효값이 하나도 없으면 기준값 없이 빈 결과")
        void emptyWhenNoValidValue() {
            NavigableMap<LocalDate, Double> series = new TreeMap<>();
            series.put(LocalDate.of(2026, 1, 1), null);
            series.put(LocalDate.of(2026, 1, 2), 0.0);

            FxIndex.Rebased rebased = FxIndex.rebase(series);

            assertThat(rebased.baseValue()).isNull();
            assertThat(rebased.values()).isEmpty();
        }

        @Test
        @DisplayName("날짜는 교집합이 아니라 합집합으로 모은다")
        void unionKeepsEveryTradingDay() {
            NavigableMap<LocalDate, Double> krw = new TreeMap<>();
            krw.put(LocalDate.of(2026, 1, 1), 1300.0);   // 미국 휴장, 한국 거래
            krw.put(LocalDate.of(2026, 1, 2), 1310.0);

            NavigableMap<LocalDate, Double> dxy = new TreeMap<>();
            dxy.put(LocalDate.of(2026, 1, 2), 100.0);
            dxy.put(LocalDate.of(2026, 1, 5), 101.0);    // 한국 휴장, 미국 거래

            assertThat(FxIndex.unionDates(krw, dxy)).containsExactly(
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 1, 2),
                    LocalDate.of(2026, 1, 5));
        }

        @Test
        @DisplayName("기간 변화율은 첫 값 대비 마지막 값")
        void changePctOverWindow() {
            NavigableMap<LocalDate, Double> series = new TreeMap<>();
            series.put(LocalDate.of(2026, 1, 2), 1000.0);
            series.put(LocalDate.of(2026, 3, 2), 1075.0);

            assertThat(FxIndex.changePct(series)).isCloseTo(7.5, within(1e-9));
        }
    }

    @Nested
    @DisplayName("SupplyConsensus")
    class SupplyConsensusTest {

        @Test
        @DisplayName("두 목록에 모두 있는 종목만 남는다")
        void keepsOnlyShared() {
            List<SupplyConsensus.Row> foreign = rows(
                    Map.of("code", "005930", "name", "삼성전자", "netAmountEok", 1200.0, "rank", 1),
                    Map.of("code", "000660", "name", "SK하이닉스", "netAmountEok", 800.0, "rank", 2));
            List<SupplyConsensus.Row> institution = rows(
                    Map.of("code", "000660", "name", "SK하이닉스", "netAmountEok", 300.0, "rank", 5),
                    Map.of("code", "035420", "name", "NAVER", "netAmountEok", 150.0, "rank", 6));

            List<Map<String, Object>> merged = SupplyConsensus.intersect(foreign, institution);

            assertThat(merged).hasSize(1);
            assertThat(merged.get(0).get("code")).isEqualTo("000660");
            assertThat(merged.get(0).get("foreignEok")).isEqualTo(800.0);
            assertThat(merged.get(0).get("institutionEok")).isEqualTo(300.0);
            assertThat(merged.get(0).get("totalEok")).isEqualTo(1100.0);
            assertThat(merged.get(0).get("foreignRank")).isEqualTo(2);
            assertThat(merged.get(0).get("institutionRank")).isEqualTo(5);
        }

        @Test
        @DisplayName("한쪽 금액이 없으면 합계를 만들지 않는다")
        void nullTotalWhenOneSideMissing() {
            List<SupplyConsensus.Row> foreign = rows(
                    Map.of("code", "005930", "name", "삼성전자", "netAmountEok", 1200.0, "rank", 1));
            List<SupplyConsensus.Row> institution = rows(
                    Map.of("code", "005930", "name", "삼성전자", "rank", 3));

            List<Map<String, Object>> merged = SupplyConsensus.intersect(foreign, institution);

            // 0으로 채우면 "기관은 안 샀다"가 아니라 "기관이 0억 샀다"로 읽힙니다.
            assertThat(merged.get(0).get("totalEok")).isNull();
        }

        @Test
        @DisplayName("순매도(음수)도 크기 순으로 정렬한다")
        void sortsSellsByMagnitude() {
            List<SupplyConsensus.Row> foreign = rows(
                    Map.of("code", "A", "name", "작게 판 종목", "netAmountEok", -100.0, "rank", 2),
                    Map.of("code", "B", "name", "크게 판 종목", "netAmountEok", -900.0, "rank", 1));
            List<SupplyConsensus.Row> institution = rows(
                    Map.of("code", "A", "name", "작게 판 종목", "netAmountEok", -50.0, "rank", 2),
                    Map.of("code", "B", "name", "크게 판 종목", "netAmountEok", -400.0, "rank", 1));

            List<Map<String, Object>> merged = SupplyConsensus.intersect(foreign, institution);

            assertThat(merged.get(0).get("code")).isEqualTo("B");
            assertThat(merged.get(0).get("totalEok")).isEqualTo(-1300.0);
        }

        @Test
        @DisplayName("종목코드가 없는 행은 이름으로 추측해 맞추지 않는다")
        void dropsRowsWithoutCode() {
            List<SupplyConsensus.Row> foreign = rows(
                    Map.of("name", "삼성전자", "netAmountEok", 1200.0, "rank", 1));
            List<SupplyConsensus.Row> institution = rows(
                    Map.of("name", "삼성전자", "netAmountEok", 300.0, "rank", 1));

            assertThat(SupplyConsensus.intersect(foreign, institution)).isEmpty();
        }
    }

    @SafeVarargs
    /** 저장본과 같은 JSON을 서비스와 같은 변환({@link FlowJson})으로 넘깁니다. */
    private static List<SupplyConsensus.Row> rows(Map<String, Object>... maps) {
        return FlowJson.rankRows(List.of(maps).stream().map(map -> (JsonNode) MAPPER.valueToTree(map)).toList());
    }
}
