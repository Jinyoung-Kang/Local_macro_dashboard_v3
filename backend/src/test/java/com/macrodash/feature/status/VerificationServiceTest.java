package com.macrodash.feature.status;

import com.macrodash.collector.CollectorClient;
import com.macrodash.feature.publicdata.CalendarService;
import com.macrodash.read.StoreReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 교차 검증의 시간 조건이 휴장일을 아는지(QA-111). 장중 가집계 TR은 휴장일에 빈 값을 주므로, 평일 장 시간이라도
 * 휴장일이면 대조하지 않고 '확인 못 함'이어야 합니다 — 그러지 않으면 운영자 진단에 거짓 경보가 납니다.
 */
class VerificationServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String RANKING = "KOSPI 외국인 순매수 1위 종목";

    @Test
    @DisplayName("QA-111: 평일 공휴일 장 시간에는 수급 1위 대조를 건너뛴다(휴장)")
    void rankingComparisonIsSkippedOnPublicHoliday() throws Exception {
        CollectorClient collector = mock(CollectorClient.class);
        when(collector.verificationReadings(anyString(), anyString(), anyString())).thenReturn(Optional.of(
                new ObjectMapper().readTree("{\"keys\": {\"krx\": false, \"kis\": true, \"toss\": false}, \"rankingTop\": {}}")));
        CalendarService calendar = mock(CalendarService.class);
        when(calendar.holidayDates()).thenReturn(Set.of("2026-10-09"));   // 금요일, 한글날
        VerificationService service = new VerificationService(mock(StoreReader.class), collector, calendar);

        Map<String, Object> out = service.run(ZonedDateTime.of(LocalDateTime.of(2026, 10, 9, 11, 0), KST));

        Map<?, ?> ranking = ((List<?>) out.get("results")).stream()
                .map(Map.class::cast).filter(row -> RANKING.equals(row.get("name"))).findFirst().orElseThrow();
        assertThat(ranking.get("verdict")).isEqualTo("skipped");
        assertThat(String.valueOf(ranking.get("note"))).contains("휴장");
    }
}
