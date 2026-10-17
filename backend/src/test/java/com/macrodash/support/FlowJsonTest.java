package com.macrodash.support;

import com.macrodash.analytics.FlowRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장본(JSON) → 수급 계산 입력 변환.
 *
 * <p>예전에 계산 코드가 JSON을 직접 읽던 규칙을 그대로 지키는지 고정합니다:
 * 금액은 정수일 때만 읽고, 없거나 null인 분류는 "모름"이며, 0으로 채우지 않습니다.
 */
class FlowJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("금액은 정수일 때만 읽고, 없거나 null이거나 소수면 모름(null)")
    void amountsAreReadOnlyWhenIntegral() throws Exception {
        FlowRecord record = FlowJson.record(MAPPER.readTree("""
                {"date": "2026-09-25", "updatedAt": "2026-09-25T18:10:00+09:00",
                 "investors": {"foreigner": {"buy": 10, "sell": 4, "net": 6},
                               "institution": {"buy": 1.5, "sell": null, "net": "7"},
                               "individual": null},
                 "breakdown": null,
                 "foreignerHoldingRate": 0.5089}
                """));

        assertThat(record.date()).isEqualTo("2026-09-25");
        assertThat(record.updatedAt()).isEqualTo("2026-09-25T18:10:00+09:00");
        assertThat(record.investors().get("foreigner")).isEqualTo(new FlowRecord.Amounts(10L, 4L, 6L));
        assertThat(record.investors().get("institution")).isEqualTo(new FlowRecord.Amounts(null, null, null));
        assertThat(record.investors()).doesNotContainKey("individual");     // null 분류는 "모름"
        assertThat(record.breakdown()).isEmpty();
        assertThat(record.foreignerHoldingRate()).isEqualTo(0.5089);
    }

    @Test
    @DisplayName("객체가 아닌 기록은 모든 칸이 모름인 기록이 된다 (예외·0 없음)")
    void nonObjectRecordIsAllUnknown() throws Exception {
        List<FlowRecord> records = FlowJson.records(List.of(MAPPER.readTree("null"), MAPPER.readTree("3")));

        assertThat(records).hasSize(2);
        for (FlowRecord record : records) {
            assertThat(record.date()).isNull();
            assertThat(record.investors()).isEmpty();
            assertThat(record.foreignerHoldingRate()).isNull();
        }
    }

    @Test
    @DisplayName("상위 목록·선물 행: 숫자가 아닌 값은 null")
    void rowsKeepUnknownsAsNull() throws Exception {
        var rank = FlowJson.rankRows(List.of(MAPPER.readTree("""
                {"code": "005930", "name": "삼성전자", "price": null, "changePct": "-1.2",
                 "netAmountEok": 12.5, "rank": 3}
                """))).get(0);
        assertThat(rank.code()).isEqualTo("005930");
        assertThat(rank.price()).isNull();
        assertThat(rank.changePct()).isNull();
        assertThat(rank.netAmountEok()).isEqualTo(12.5);
        assertThat(rank.rank()).isEqualTo(3.0);

        var futures = FlowJson.futuresRows(List.of(MAPPER.readTree("""
                {"investor": "외국인 (스마트머니)", "netToday": 1200, "net5d": null}
                """))).get(0);
        assertThat(futures.investor()).isEqualTo("외국인 (스마트머니)");
        assertThat(futures.netToday()).isEqualTo(1200.0);
        assertThat(futures.net5d()).isNull();
        assertThat(futures.net20d()).isNull();
    }

    @Test
    @DisplayName("QA-076 시장을 받은 날 — 시장별 fetchedAt(KST) 우선, 예전 저장본은 저장본 수집 시각, 이어받았는데 시각을 모르면 null")
    void marketFetchedOnPrefersPerMarketTime() throws Exception {
        java.time.Instant collected = java.time.Instant.parse("2099-03-11T15:30:00Z");     // 03-12 00:30 KST

        assertThat(FlowJson.marketFetchedOn(MAPPER.readTree("""
                {"fetchedAt": "2099-03-11T02:00:00.123456+00:00", "isStale": true}
                """), collected)).as("이어받은 시장은 그 시장을 받은 날").isEqualTo(java.time.LocalDate.of(2099, 3, 11));
        assertThat(FlowJson.marketFetchedOn(MAPPER.readTree("{\"records\": []}"), collected))
                .as("fetchedAt이 없는 예전 저장본").isEqualTo(java.time.LocalDate.of(2099, 3, 12));
        assertThat(FlowJson.marketFetchedOn(MAPPER.readTree("{\"isStale\": true}"), collected))
                .as("이어받았는데 받은 시각을 모름 — 확정으로 보지 않음").isNull();
        assertThat(FlowJson.marketFetchedOn(MAPPER.readTree("{\"fetchedAt\": \"어제\"}"), collected))
                .as("읽을 수 없는 시각은 없는 것과 같음").isEqualTo(java.time.LocalDate.of(2099, 3, 12));
        assertThat(FlowJson.marketFetchedOn(null, null)).isNull();
    }
}
