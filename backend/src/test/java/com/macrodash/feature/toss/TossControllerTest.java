package com.macrodash.feature.toss;

import com.macrodash.collector.CollectorClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 토스 연결 테스트 응답 모양 고정 — 수집기 결과를 그대로 넘기고, 못 닿으면 ok=false. */
class TossControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("수집기 결과의 필드를 그대로 넘긴다")
    void passesCollectorPayloadThrough() throws Exception {
        CollectorClient collector = mock(CollectorClient.class);
        when(collector.tossExchangeRate("USD", "KRW")).thenReturn(Optional.of(MAPPER.readTree(
                "{\"ok\": true, \"rate\": 1385.2, \"source\": \"toss\"}")));

        Map<String, Object> out = controller(collector).tossExchangeRate("USD", "KRW");

        assertThat(out).containsOnlyKeys("ok", "rate", "source");
        assertThat(out.get("rate").toString()).isEqualTo("1385.2");
    }

    @Test
    @DisplayName("수집기에 못 닿으면 ok=false와 안내 문구")
    void collectorUnreachable() {
        CollectorClient collector = mock(CollectorClient.class);
        when(collector.tossDiagnostics()).thenReturn(Optional.empty());
        when(collector.tossIndices("KOSPI")).thenReturn(Optional.empty());

        assertThat(controller(collector).tossDiagnostics()).isEqualTo(Map.of(
                "ok", false, "message", "수집기에 연결하지 못했습니다. 토스 진단은 수집기가 수행합니다."));
        assertThat(controller(collector).tossIndices("KOSPI")).isEqualTo(Map.of(
                "ok", false, "message", "수집기에 연결하지 못했습니다."));
    }

    private static TossController controller(CollectorClient collector) {
        return new TossController(new TossService(collector));
    }
}
