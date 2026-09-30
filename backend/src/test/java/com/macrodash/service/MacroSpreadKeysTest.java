package com.macrodash.service;

import com.macrodash.collector.CollectorClient;
import com.macrodash.config.AppProperties;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import com.macrodash.store.StoreReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 장단기 금리차 응답의 **키 이름**이 만기를 속이지 않는지 고정합니다.
 *
 * <p>같은 구조를 10Y−2Y와 30Y−2Y 두 곳에 씁니다. 예전에는 편의를 위해
 * {@code us10y}/{@code us02y}라는 이름을 함께 내려보냈는데, 30Y−2Y 블록에서는
 * 그 {@code us10y} 칸에 **30년물** 값이 들어갔습니다. 받는 쪽(화면·AI 요약)은
 * 이름만 보고 10년물이라고 읽으므로, 잘못된 라벨이 그대로 문장이 됩니다.
 *
 * <p>또 하나: 수집기가 {@code rates.us30y}를 내려보내면 30Y−2Y 카드의 스크래핑
 * 패널이 실제로 채워져야 합니다. 예전에는 수집기가 30년물을 rates에 담지
 * 않아, 수집에 성공하고도 화면에는 "수집 실패"가 떴습니다.
 */
class MacroSpreadKeysTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 2년·10년·30년이 모두 담긴 매크로 저장본. */
    private static final String PAYLOAD = """
            {
              "categories": [],
              "rates": {
                "us02y": {"current": 3.60, "previous": 3.55},
                "us10y": {"current": 4.10, "previous": 4.00},
                "us30y": {"current": 4.70, "previous": 4.65}
              }
            }
            """;

    private Map<String, Object> overview() throws Exception {
        JsonNode payload = MAPPER.readTree(PAYLOAD);
        StoreReader store = mock(StoreReader.class);
        when(store.readMode()).thenReturn(AppProperties.ReadMode.STORE_ONLY);
        when(store.read(anyString(), anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(store.read(eq(Datasets.SNAP_MACRO_COLLECTED), anyLong(), anyString()))
                .thenReturn(Optional.of(new Snapshot(
                        Datasets.SNAP_MACRO_COLLECTED, payload,
                        "json", "ok", null, Instant.now())));

        MacroService macro = new MacroService(store, mock(CollectorClient.class));
        return macro.overview();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        assertThat(value).as("%s 블록", key).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @Test
    @DisplayName("30Y−2Y 스크래핑 패널이 30년물 값으로 채워진다")
    void thirtyYearScrapedPanelIsPopulated() throws Exception {
        Map<String, Object> spreads = child(overview(), "spreads");
        Map<String, Object> scraped = child(child(spreads, "official30y2y"), "scraped");

        assertThat(scraped.get("longKey")).isEqualTo("us30y");
        assertThat(scraped.get("shortKey")).isEqualTo("us02y");
        assertThat((Double) scraped.get("longValue")).isEqualTo(4.70);
        // 4.70 − 3.60 = 1.10
        assertThat((Double) scraped.get("spread")).isEqualTo(1.10, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("스크래핑 스프레드는 만기가 박힌 키를 내려보내지 않는다")
    void scrapedSpreadDoesNotCarryHardCodedMaturityKeys() throws Exception {
        Map<String, Object> spreads = child(overview(), "spreads");

        for (String block : new String[]{"realtime", "official10y2y", "official30y2y"}) {
            Map<String, Object> scraped = "realtime".equals(block)
                    ? child(spreads, block)
                    : child(child(spreads, block), "scraped");
            assertThat(scraped)
                    .as("%s: 만기는 longKey/shortKey로만 알려야 합니다", block)
                    .doesNotContainKeys("us02y", "us10y", "us30y");
        }
    }
}
