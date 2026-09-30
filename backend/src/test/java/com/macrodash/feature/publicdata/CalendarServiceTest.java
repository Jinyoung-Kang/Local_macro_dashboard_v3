package com.macrodash.feature.publicdata;

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

class CalendarServiceTest {

    private static CalendarService serviceWith(String json) throws Exception {
        StoreReader store = mock(StoreReader.class);
        Optional<Snapshot> snapshot = json == null ? Optional.empty() : Optional.of(new Snapshot(
                Datasets.SNAP_KR_HOLIDAYS, new ObjectMapper().readTree(json), "json", "ok", null, Instant.now()));
        when(store.read(eq(Datasets.SNAP_KR_HOLIDAYS), anyLong(), anyString())).thenReturn(snapshot);
        return new CalendarService(store);
    }

    @Test
    @DisplayName("저장된 연도별 공휴일을 그대로 전달하고, 형식이 틀린 날짜는 버린다")
    @SuppressWarnings("unchecked")
    void passesThroughValidDates() throws Exception {
        CalendarService service = serviceWith("""
                {"source":"천문연","years":{"2026":{"announced":true,"holidays":[
                  {"date":"2026-08-17","name":"대체공휴일(광복절)"},
                  {"date":"20260603","name":"형식 오류"}]}}}
                """);

        Map<String, Object> out = service.krHolidays();

        assertThat(out.get("available")).isEqualTo(true);
        Map<String, Object> year = (Map<String, Object>) ((Map<String, Object>) out.get("years")).get("2026");
        assertThat((List<Map<String, String>>) year.get("holidays"))
                .extracting(day -> day.get("date"))
                .containsExactly("2026-08-17");
        assertThat(year.get("announced")).isEqualTo(true);
    }

    @Test
    @DisplayName("저장본이 없으면 available=false — 화면은 내장 규칙으로 계속 동작")
    void missingSnapshot() throws Exception {
        Map<String, Object> out = serviceWith(null).krHolidays();
        assertThat(out.get("available")).isEqualTo(false);
        assertThat((Map<?, ?>) out.get("years")).isEmpty();
    }
}
