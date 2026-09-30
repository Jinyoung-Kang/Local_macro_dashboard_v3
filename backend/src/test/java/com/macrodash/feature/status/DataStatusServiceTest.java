package com.macrodash.feature.status;

import com.macrodash.collector.CollectorClient;
import com.macrodash.store.StoreReader;
import com.macrodash.store.StoreRepository;
import com.macrodash.support.InvalidRequestException;
import com.macrodash.support.UpstreamUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 태스크 수동 실행 — 끝날 때까지 기다리지 않고 시작만 합니다(BUG-03).
 *
 * <p>예전에는 수집기 대기 한도(90초)보다 오래 걸리는 태스크(13F 등)를 "다시 실행"하면
 * 실제로는 수집 중인데 "수집기에 연결하지 못했습니다"로 표시됐습니다(재현: 한도 3초·작업 5초).
 */
class DataStatusServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CollectorClient collector = mock(CollectorClient.class);
    private final StoreRepository repository = mock(StoreRepository.class);
    private final DataStatusService service =
            new DataStatusService(repository, mock(StoreReader.class), collector);

    private JsonNode tasks(String... names) {
        var array = mapper.createArrayNode();
        for (String name : names) {
            array.addObject().put("name", name);
        }
        var root = mapper.createObjectNode();
        root.set("tasks", array);
        return root;
    }

    @Test
    @DisplayName("기다리지 않고(wait=false) 시작하고, 끝난 것을 가릴 기준 시각을 돌려준다")
    void startsWithoutWaitingAndReturnsBaseline() {
        Instant previous = Instant.parse("2026-09-27T00:00:00.123Z");
        when(collector.tasks()).thenReturn(Optional.of(tasks("sec_13f", "fred_series")));
        when(repository.readTaskHistory("sec_13f", 1))
                .thenReturn(List.of(Map.of("started_at", Timestamp.from(previous))));
        when(collector.runTask("sec_13f", false)).thenReturn(Optional.of(mapper.createObjectNode()));

        Map<String, Object> result = service.startTask("sec_13f");

        assertThat(result).containsEntry("accepted", true).containsEntry("task", "sec_13f");
        assertThat(result.get("baselineStartedAt")).isEqualTo(previous.toString());
        verify(collector).runTask("sec_13f", false);
        verify(collector, never()).runTask("sec_13f", true);
    }

    @Test
    @DisplayName("기준 시각은 화면이 확인하는 경로(수집기 실행 이력)와 같은 곳에서 읽는다")
    void baselineComesFromTheSamePathAsPolling() {
        var history = mapper.createObjectNode();
        history.putArray("history").addObject().put("startedAt", "2026-09-26T00:00:00+00:00");
        when(collector.tasks()).thenReturn(Optional.of(tasks("sec_13f")));
        when(collector.taskHistory("sec_13f", 1)).thenReturn(Optional.of(history));
        when(collector.runTask("sec_13f", false)).thenReturn(Optional.of(mapper.createObjectNode()));

        assertThat(service.startTask("sec_13f"))
                .containsEntry("baselineStartedAt", "2026-09-26T00:00:00+00:00");
        verify(repository, never()).readTaskHistory(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("실행 기록이 한 번도 없던 태스크는 기준 시각이 null")
    void baselineIsNullWithoutHistory() {
        when(collector.tasks()).thenReturn(Optional.of(tasks("kr_holidays")));
        when(repository.readTaskHistory("kr_holidays", 1)).thenReturn(List.of());
        when(collector.runTask("kr_holidays", false)).thenReturn(Optional.of(mapper.createObjectNode()));

        assertThat(service.startTask("kr_holidays")).containsEntry("baselineStartedAt", null);
    }

    @Test
    @DisplayName("수집기가 모르는 태스크는 400 — 실행을 요청하지 않는다")
    void unknownTaskIsRejected() {
        when(collector.tasks()).thenReturn(Optional.of(tasks("sec_13f")));

        assertThatThrownBy(() -> service.startTask("nope"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("nope");
        verify(collector, never()).runTask(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("수집기에 닿지 못하면 502 — 200에 ok:false로 숨기지 않는다")
    void unreachableCollectorIsBadGateway() {
        when(collector.tasks()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startTask("sec_13f"))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    @DisplayName("실행 요청이 거절되면 502")
    void rejectedRunIsBadGateway() {
        when(collector.tasks()).thenReturn(Optional.of(tasks("sec_13f")));
        when(repository.readTaskHistory("sec_13f", 1)).thenReturn(List.of());
        when(collector.runTask("sec_13f", false)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startTask("sec_13f"))
                .isInstanceOf(UpstreamUnavailableException.class);
    }
}
