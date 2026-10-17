package com.macrodash.feature.institution;

import com.macrodash.analytics.HoldingsDiff;
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
 * QA-085 '직전 분기'는 저장본의 바로 앞 항목이 아니라 <b>바로 앞 분기 말일</b>의 공시입니다.
 *
 * <p>노르웨이 저장본은 1·3분기가 빠져 2026-06-30 다음 항목이 2025-12-31입니다(6개월 간격). 그 항목을 직전 분기로
 * 쓰면 6개월치 변화가 '직전 분기 대비'·'이번 분기 신규 매수'·'분기 회전율'·'신규 편입 알림'으로 나갑니다.
 */
class Sec13FQuarterGapTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NPS = "0001608046";
    private static final String NORGES = "0001374170";

    /** 노르웨이: 2026-06-30 다음이 2025-12-31(1분기 빠짐). X는 2026-06-30에만, Y는 2025-12-31에만. */
    private static final String NORGES_WITH_GAP = """
            [{"reportDate":"2026-06-30","holdings":[
                {"name":"X CORP","cusip":"X00000001","class":"COM","value":100,"shares":10,"weight":100.0}]},
             {"reportDate":"2025-12-31","holdings":[
                {"name":"Y CORP","cusip":"Y00000001","class":"COM","value":100,"shares":10,"weight":100.0}]}]
            """;

    /** 국민연금: 연속된 두 분기. X를 이번 분기에 새로 담았습니다. */
    private static final String NPS_CONSECUTIVE = """
            [{"reportDate":"2026-06-30","holdings":[
                {"name":"X CORP","cusip":"X00000001","class":"COM","value":100,"shares":10,"weight":50.0},
                {"name":"Y CORP","cusip":"Y00000001","class":"COM","value":100,"shares":10,"weight":50.0}]},
             {"reportDate":"2026-03-31","holdings":[
                {"name":"Y CORP","cusip":"Y00000001","class":"COM","value":100,"shares":10,"weight":100.0}]}]
            """;

    static StoreReader store(Map<String, String> quartersByCik) {
        StoreReader store = mock(StoreReader.class);
        when(store.read(anyString(), anyLong(), anyString())).thenReturn(Optional.empty());
        quartersByCik.forEach((cik, json) ->
                when(store.read(eq(Datasets.sec13f(cik, Datasets.MAX_TRACKED_QUARTERS)), anyLong(), anyString()))
                        .thenReturn(Optional.of(new Snapshot(Datasets.sec13f(cik, Datasets.MAX_TRACKED_QUARTERS),
                                MAPPER.readTree("{\"cik\":\"" + cik + "\",\"quarters\":" + json + "}"),
                                "json", "ok", null, Instant.now()))));
        return store;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> response, String field) {
        return (List<Map<String, Object>>) response.get(field);
    }

    @Test
    @DisplayName("QA-085 포트폴리오 — 다음 저장 분기가 바로 앞 분기가 아니면 '비교 데이터 없음'(6개월 변화를 신규 매수로 부르지 않음)")
    void portfolioDoesNotCompareAcrossAMissingQuarter() {
        Sec13FService service = new Sec13FService(store(Map.of(NORGES, NORGES_WITH_GAP)));

        List<Map<String, Object>> holdings = list(service.portfolio(NORGES, 8, 50), "holdings");

        assertThat(holdings).extracting(row -> row.get("action")).containsExactly(HoldingsDiff.NO_PREVIOUS);
    }

    @Test
    @DisplayName("QA-085 공통 신규 매수·교집합 — 직전 분기가 빠진 기관의 6개월 변화를 '이번 분기 매수'로 세지 않는다")
    void consensusAndNewBuysSkipAMissingQuarter() {
        Sec13FService service = new Sec13FService(store(Map.of(NPS, NPS_CONSECUTIVE, NORGES, NORGES_WITH_GAP)));

        assertThat(list(service.newBuys(List.of(NPS, NORGES), null, 2), "rows"))
                .as("노르웨이는 직전 분기가 없어 신규 매수를 판정할 수 없음 — X를 새로 담은 곳은 국민연금 1곳")
                .isEmpty();

        Map<String, Object> x = list(service.consensus(List.of(NPS, NORGES), null, 2, 10), "rows").stream()
                .filter(row -> "X CORP".equals(row.get("name"))).findFirst().orElseThrow();
        assertThat(x.get("holderCount")).as("지금 보유는 두 곳 그대로").isEqualTo(2);
        assertThat(x.get("buyCount")).as("동시 매수는 국민연금만").isEqualTo(1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("QA-085 구루 스타일 — 직전 분기가 빠지면 분기 회전율은 6개월치가 아니라 null")
    void turnoverIsUnknownAcrossAMissingQuarter() {
        GuruService guru = new GuruService(store(Map.of(NPS, NPS_CONSECUTIVE, NORGES, NORGES_WITH_GAP)));

        List<Map<String, Object>> rows = (List<Map<String, Object>>) guru.profiles().get("rows");

        Map<String, Object> norges = rows.stream().filter(row -> NORGES.equals(row.get("cik"))).findFirst().orElseThrow();
        Map<String, Object> nps = rows.stream().filter(row -> NPS.equals(row.get("cik"))).findFirst().orElseThrow();
        assertThat(norges.get("turnover")).as("2025-12-31 대비 100%가 아니라 모름").isNull();
        assertThat(nps.get("turnover")).as("연속 분기는 그대로 계산").isEqualTo(50.0);
    }
}
