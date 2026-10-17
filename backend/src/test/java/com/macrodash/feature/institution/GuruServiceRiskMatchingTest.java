package com.macrodash.feature.institution;

import com.macrodash.Kst;
import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * 기관 위험 분석의 이름→티커 매칭(QA-087).
 *
 * <p>13F에는 티커가 없어 이름으로 가격을 찾는데, 예전에는 매핑표 키와 정확히 같은 이름만 찾아 'NVIDIA CORPORATION'
 * (국민연금·노르웨이 등 최대 보유)이 '매핑표에 없음'으로 빠졌습니다. 이제 수집기가 13F를 저장할 때 보유마다 두 단계
 * 매칭(펀드·채권 제외)으로 티커를 붙이고, 백엔드는 그 티커를 먼저 씁니다. 티커 필드가 없는 옛 저장본은 지금처럼
 * 이름을 정확히 비교합니다. 수집기가 모른다고 정한 보유(티커 null — 펀드·채권 등)는 이름으로 다시 찾지 않습니다.
 */
class GuruServiceRiskMatchingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 오늘(KST)까지 평일 n일의 종가. */
    private static ObjectNode series(int days, double start) {
        ObjectNode node = MAPPER.createObjectNode();
        ArrayNode dates = node.putArray("dates");
        ArrayNode close = node.putArray("close");
        List<LocalDate> picked = new ArrayList<>();
        LocalDate day = Kst.today();
        while (picked.size() < days) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                picked.add(0, day);
            }
            day = day.minusDays(1);
        }
        for (int i = 0; i < picked.size(); i++) {
            dates.add(picked.get(i).toString());
            close.add(start * (1 + 0.001 * i + (i % 3) * 0.002));
        }
        return node;
    }

    private static Snapshot snapshot(String name, ObjectNode payload) {
        return new Snapshot(name, payload, "json", "ok", null, Instant.now());
    }

    @Test
    @Disabled("QA-087: 수정 전 재현 — fix 커밋에서 켬")
    @DisplayName("QA-087 보유에 붙은 티커를 먼저 쓴다 — 'NVIDIA CORPORATION'도 NVDA로 덮고, 수집기가 모른다고 정한 채권은 이름으로 덮지 않는다")
    void holdingTickerComesFirst() {
        String cik = Institutions.ciks().get(0);
        ObjectNode prices = MAPPER.createObjectNode();
        ObjectNode tickers = prices.putObject("tickers");
        tickers.set("NVDA", series(60, 100));
        tickers.set("AAPL", series(60, 200));
        tickers.set("BKNG", series(60, 300));
        tickers.set("SPY", series(60, 500));
        ObjectNode nameMap = prices.putObject("nameMap");
        nameMap.putObject("NVIDIA CORP").put("ticker", "NVDA").put("sector", "정보기술");
        nameMap.putObject("APPLE INC").put("ticker", "AAPL").put("sector", "정보기술");
        nameMap.putObject("BOOKING HLDGS INC").put("ticker", "BKNG").put("sector", "임의소비재");

        ObjectNode filings = MAPPER.createObjectNode();
        ArrayNode holdings = filings.putArray("quarters").addObject()
                .put("reportDate", "2026-06-30").put("filingDate", "2026-08-14")
                .putArray("holdings");
        holdings.addObject().put("name", "NVIDIA CORPORATION").put("cusip", "67066G104")
                .put("weight", 60.0).put("value", 60.0).put("ticker", "NVDA");
        // 티커 필드가 없는 옛 저장본 모양 — 지금처럼 이름을 정확히 비교합니다.
        holdings.addObject().put("name", "APPLE INC").put("cusip", "037833100")
                .put("weight", 30.0).put("value", 30.0);
        // 수집기가 채권(전환사채)이라 티커를 붙이지 않은 행. 이름이 매핑표 키와 같아도 주가로 덮지 않습니다.
        holdings.addObject().put("name", "BOOKING HLDGS INC").put("cusip", "09857LAN8")
                .put("weight", 10.0).put("value", 10.0).putNull("ticker");

        StoreReader store = mock(StoreReader.class);
        when(store.read(anyString(), anyLong(), anyString())).thenReturn(Optional.empty());
        when(store.read(eq(Datasets.SNAP_EQUITY_HISTORY), anyLong(), anyString()))
                .thenReturn(Optional.of(snapshot(Datasets.SNAP_EQUITY_HISTORY, prices)));
        String filingName = Datasets.sec13f(cik, Datasets.MAX_TRACKED_QUARTERS);
        when(store.read(eq(filingName), anyLong(), anyString()))
                .thenReturn(Optional.of(snapshot(filingName, filings)));

        Map<String, Object> risk = new GuruService(store).risk(cik, "SPY", 1);

        @SuppressWarnings("unchecked")
        Map<String, Object> coverage = (Map<String, Object>) risk.get("coverage");
        assertThat(coverage.get("holdings")).isEqualTo(2);
        assertThat((double) coverage.get("weight")).isEqualTo(90.0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> uncovered = (List<Map<String, Object>>) coverage.get("uncovered");
        assertThat(uncovered).singleElement()
                .satisfies(row -> assertThat(row.get("name")).isEqualTo("BOOKING HLDGS INC"));

        // 섹터는 매핑표에서 그 티커의 섹터를 씁니다(보유 이름이 매핑표 키와 달라도).
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contributions = (List<Map<String, Object>>) risk.get("contributions");
        assertThat(contributions).filteredOn(row -> "NVDA".equals(row.get("ticker")))
                .singleElement().satisfies(row -> {
                    assertThat(row.get("sector")).isEqualTo("정보기술");
                    assertThat(row.get("name")).isEqualTo("NVIDIA CORPORATION");
                });
    }
}
