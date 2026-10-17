package com.macrodash.feature.positioning;

import com.macrodash.Kst;
import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import com.macrodash.store.StoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 시장 전체 투자자별 매매대금 응답 — 수집 실패로 이어받은 시장의 표시(QA-076).
 *
 * <p>저장본 신선도(collectedAtKst)는 마지막으로 받은 시장의 것이라, 한 시장이 멈춰도 화면은 방금 받은 시각을 보여 줍니다.
 * 그래서 시장마다 받은 시각과 이전 값 표시를 함께 내립니다.
 */
class KrFlowsServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("QA-076 이어받은 시장은 isStale·사유·받은 시각을 싣고, 그 시장을 받은 날 기준으로 잠정치를 표시한다")
    @SuppressWarnings("unchecked")
    void carriedOverMarketIsMarkedAndProvisionalByItsFetchDay() throws Exception {
        LocalDate yesterday = Kst.today().minusDays(1);
        String kosdaqFetchedAt = yesterday.atTime(11, 0).atZone(Kst.ZONE).toOffsetDateTime().toString();   // 어제 장중
        String kospiFetchedAt = Instant.now().toString();
        JsonNode payload = MAPPER.readTree("""
                {"markets":{
                  "KOSPI":{"fetchedAt":"%s","records":[{"date":"%s","investors":{"foreigner":{"net":100}}}]},
                  "KOSDAQ":{"fetchedAt":"%s","isStale":true,"staleReason":"KOSDAQ: HTTP 500","staleSince":"%s",
                    "records":[{"date":"%s","investors":{"foreigner":{"net":100}}}]}}}
                """.formatted(kospiFetchedAt, yesterday, kosdaqFetchedAt, kosdaqFetchedAt, yesterday));
        StoreReader store = mock(StoreReader.class);
        when(store.read(eq(Datasets.SNAP_TOSS_MARKET_FLOWS), anyLong(), anyString()))
                .thenReturn(Optional.of(new Snapshot(Datasets.SNAP_TOSS_MARKET_FLOWS, payload, "json", "ok", null,
                        Instant.now())));

        Map<String, Object> out = new KrFlowsService(store, mock(StoreRepository.class), mock(KrxService.class))
                .marketFlows();

        Map<String, Object> markets = (Map<String, Object>) out.get("markets");
        Map<String, Object> kosdaq = (Map<String, Object>) markets.get("KOSDAQ");
        assertThat(kosdaq.get("isStale")).isEqualTo(true);
        assertThat(kosdaq.get("staleReason")).isEqualTo("KOSDAQ: HTTP 500");
        assertThat(kosdaq.get("fetchedAt")).isEqualTo(kosdaqFetchedAt);
        assertThat(kosdaq.get("provisional")).as("어제 장중에 받고 멈춘 어제 기록은 아직 잠정치").isEqualTo(true);
        Map<String, Object> kospi = (Map<String, Object>) markets.get("KOSPI");
        assertThat(kospi.get("isStale")).isEqualTo(false);
        assertThat(kospi.get("provisional")).as("새로 받은 시장은 지금처럼 오늘 기준").isEqualTo(false);
    }
}
