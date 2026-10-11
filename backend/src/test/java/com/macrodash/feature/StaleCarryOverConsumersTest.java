package com.macrodash.feature;

import com.macrodash.Kst;
import com.macrodash.feature.institution.GuruService;
import com.macrodash.feature.institution.Institutions;
import com.macrodash.feature.macro.SectorService;
import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
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
 * 수집기가 일부 실패 때 직전 저장본에서 이어받은 항목(isStale, QA-032·QA-056)을 받는 쪽이 '지금 값'처럼 쓰지 않는다(QA-065).
 *
 * <p>업종 순위는 멈춘 ETF의 수익률을 표시 없이 최신 ETF와 같은 표에 섞었고, 기관 위험 분석은 모든 보유 종목 날짜의
 * 교집합으로 계산해 멈춘 종목 하나가 포트폴리오 전체의 계산 구간을 그 종목의 마지막 날에 묶었습니다.
 */
class StaleCarryOverConsumersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 오늘(KST)까지 평일 n일의 종가. stale이면 마지막 3일이 없는(멈춘) 계열에 isStale을 붙입니다. */
    private static ObjectNode series(int days, double start, boolean stale) {
        ObjectNode node = MAPPER.createObjectNode();
        ArrayNode dates = node.putArray("dates");
        ArrayNode close = node.putArray("close");
        LocalDate day = Kst.today();
        List<LocalDate> picked = new java.util.ArrayList<>();
        while (picked.size() < days) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                picked.add(0, day);
            }
            day = day.minusDays(1);
        }
        int end = stale ? picked.size() - 3 : picked.size();
        for (int i = 0; i < end; i++) {
            dates.add(picked.get(i).toString());
            close.add(start * (1 + 0.001 * i + (i % 3) * 0.002));
        }
        if (stale) {
            node.put("isStale", true).put("staleReason", "429");
        }
        return node;
    }

    private static Snapshot snapshot(String name, ObjectNode payload) {
        return new Snapshot(name, payload, "json", "ok", null, Instant.now());
    }

    @Test
    @DisplayName("QA-065 업종 순위: 이어받은(이전 값) ETF 행에 isStale을 싣는다")
    void sectorRowsCarryStaleFlag() {
        ObjectNode payload = MAPPER.createObjectNode();
        ObjectNode tickers = payload.putObject("tickers");
        tickers.set("XLK", series(60, 100, false));
        tickers.set("XLC", series(60, 80, true));
        StoreReader store = mock(StoreReader.class);
        when(store.read(eq(Datasets.SNAP_SECTOR_HISTORY), anyLong(), anyString()))
                .thenReturn(Optional.of(snapshot(Datasets.SNAP_SECTOR_HISTORY, payload)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sectors = (List<Map<String, Object>>) new SectorService(store).momentum().get("sectors");

        Map<String, Object> xlk = sectors.stream().filter(r -> "XLK".equals(r.get("ticker"))).findFirst().orElseThrow();
        Map<String, Object> xlc = sectors.stream().filter(r -> "XLC".equals(r.get("ticker"))).findFirst().orElseThrow();
        assertThat(xlc.get("isStale")).isEqualTo(true);
        assertThat(xlk.get("isStale")).isEqualTo(false);
    }

    @Test
    @DisplayName("QA-065 기관 위험 분석: 이전 값 종목은 계산에서 빼고 '가격 수집 실패'로 커버리지에 적는다")
    void staleHoldingIsExcludedFromRisk() {
        String cik = Institutions.ciks().get(0);
        ObjectNode prices = MAPPER.createObjectNode();
        ObjectNode tickers = prices.putObject("tickers");
        tickers.set("AAPL", series(60, 200, false));
        tickers.set("MSFT", series(60, 400, true));
        tickers.set("SPY", series(60, 500, false));
        ObjectNode nameMap = prices.putObject("nameMap");
        nameMap.putObject("APPLE INC").put("ticker", "AAPL").put("sector", "Technology");
        nameMap.putObject("MICROSOFT CORP").put("ticker", "MSFT").put("sector", "Technology");

        ObjectNode filings = MAPPER.createObjectNode();
        ObjectNode quarter = filings.putArray("quarters").addObject()
                .put("reportDate", "2026-06-30").put("filingDate", "2026-08-14");
        ArrayNode holdings = quarter.putArray("holdings");
        holdings.addObject().put("name", "APPLE INC").put("cusip", "037833100").put("weight", 60.0).put("value", 60.0);
        holdings.addObject().put("name", "MICROSOFT CORP").put("cusip", "594918104").put("weight", 40.0).put("value", 40.0);

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
        assertThat(coverage.get("holdings")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> uncovered = (List<Map<String, Object>>) coverage.get("uncovered");
        assertThat(uncovered).singleElement().satisfies(row -> {
            assertThat(row.get("name")).isEqualTo("MICROSOFT CORP");
            assertThat(String.valueOf(row.get("reason"))).contains("가격 수집 실패");
        });
    }
}
