package com.macrodash.service;

import com.macrodash.collector.CollectorClient;
import com.macrodash.config.AppProperties;
import com.macrodash.store.StoreReader;
import com.macrodash.store.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 상태 화면의 실패 사유(detail)에 섞인 비밀값을 가린다 (SEC-05).
 *
 * <p>사유는 예외 문구 그대로라 요청 URL(쿼리의 API 키)이 섞일 수 있고, 화면에 나가 복사됩니다.
 * 예전에는 오류 모음({@link StatusIssues})만 가리고, 상태 요약·실행 이력은 그대로 내보냈습니다.
 */
class DataStatusRedactionTest {

    private static final String LEAK =
            "HTTPError: 500 for url: https://apis.data.go.kr/x?serviceKey=SECRET123&pageNo=1";

    private final ObjectMapper mapper = new ObjectMapper();
    private final CollectorClient collector = mock(CollectorClient.class);
    private final StoreRepository repository = mock(StoreRepository.class);
    private final StoreReader reader = mock(StoreReader.class);
    private final DataStatusService service = new DataStatusService(repository, reader, collector);

    @BeforeEach
    void setUp() {
        when(reader.readMode()).thenReturn(AppProperties.ReadMode.AUTO);
    }

    private static Map<String, Object> row(String task) {
        return new HashMap<>(Map.of("task", task, "status", "error", "detail", LEAK));
    }

    @Test
    @DisplayName("수집기가 없어 DB에서 바로 읽은 실패 사유도 가린다")
    void databaseFallbackRedactsDetails() {
        when(repository.readLastRun()).thenReturn(Optional.of(row("fsc_prices")));
        when(repository.readTaskSummary()).thenReturn(List.of(row("fsc_prices")));
        when(repository.readTaskHistory("fsc_prices", 50)).thenReturn(List.of(row("fsc_prices")));

        String status = String.valueOf(service.status());
        String history = String.valueOf(service.taskHistory("fsc_prices", 50));

        assertThat(status).doesNotContain("SECRET123").contains("serviceKey=***");
        assertThat(history).doesNotContain("SECRET123").contains("serviceKey=***");
    }

    @Test
    @DisplayName("수집기가 준 요약·이력의 실패 사유도 한 번 더 가린다 (심층 방어)")
    void collectorPayloadIsRedactedAgain() throws Exception {
        JsonNode status = mapper.readTree("""
                {"lastRun": {"status": "fail", "detail": "%s"},
                 "taskSummary": [{"task": "fsc_prices", "detail": "%s"}]}
                """.formatted(LEAK, LEAK));
        JsonNode history = mapper.readTree("""
                {"history": [{"task": "fsc_prices", "detail": "%s"}, {"task": "fx_history", "detail": null}]}
                """.formatted(LEAK));
        when(collector.status()).thenReturn(Optional.of(status));
        when(collector.taskHistory("fsc_prices", 50)).thenReturn(Optional.of(history));

        assertThat(String.valueOf(service.status())).doesNotContain("SECRET123").contains("serviceKey=***");
        assertThat(String.valueOf(service.taskHistory("fsc_prices", 50)))
                .doesNotContain("SECRET123")
                .contains("\"detail\":null");   // 사유가 없는 기록은 그대로
    }
}
