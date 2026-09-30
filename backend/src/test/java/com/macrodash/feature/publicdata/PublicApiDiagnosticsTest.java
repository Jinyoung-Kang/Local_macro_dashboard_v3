package com.macrodash.feature.publicdata;

import com.macrodash.collector.CollectorClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 공공 API 연결 진단 응답 모양 고정 — available을 앞에 붙이고 수집기 결과를 그대로 넘깁니다. */
class PublicApiDiagnosticsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("수집기 결과 앞에 available=true를 붙여 그대로 넘긴다")
    void passesThroughWithAvailable() throws Exception {
        CollectorClient collector = mock(CollectorClient.class);
        when(collector.publicApiDiagnostics()).thenReturn(Optional.of(MAPPER.readTree(
                "{\"services\": [{\"name\": \"특일정보\", \"ok\": true}], \"keyConfigured\": true}")));

        Map<String, Object> out = controller(collector).publicApiDiagnostics();

        assertThat(out.keySet()).containsExactly("available", "services", "keyConfigured");
        assertThat(out.get("available")).isEqualTo(true);
    }

    @Test
    @DisplayName("수집기에 못 닿으면 available=false와 안내 문구")
    void collectorUnreachable() {
        CollectorClient collector = mock(CollectorClient.class);
        when(collector.publicApiDiagnostics()).thenReturn(Optional.empty());

        Map<String, Object> out = controller(collector).publicApiDiagnostics();

        assertThat(out.keySet()).containsExactly("available", "message");
        assertThat(out).containsEntry("available", false)
                .containsEntry("message", "수집기에 연결하지 못했습니다. 진단은 수집기가 수행합니다.");
    }

    private static PublicDataController controller(CollectorClient collector) {
        return new PublicDataController(null, null, null, new PublicApiStatusService(collector));
    }
}
