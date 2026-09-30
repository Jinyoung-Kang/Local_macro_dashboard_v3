package com.macrodash.feature.positioning;

import com.macrodash.collector.CollectorClient;
import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import com.macrodash.store.StoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RadarOptionsTest {

    private static Map<String, Object> optionsWith(Optional<Snapshot> universe) {
        StoreRepository repository = mock(StoreRepository.class);
        when(repository.readSnapshot(Datasets.SNAP_TOSS_RADAR_UNIVERSE)).thenReturn(universe);
        return new RadarService(mock(StoreReader.class), repository, mock(CollectorClient.class)).options();
    }

    private static Snapshot universe(Instant collectedAt) throws Exception {
        return new Snapshot(Datasets.SNAP_TOSS_RADAR_UNIVERSE,
                new ObjectMapper().readTree("{\"stocks\":[]}"), "json", "ok", null, collectedAt);
    }

    @Test
    @DisplayName("토스 폴백 저장본이 없으면 외국인·기관만 고를 수 있다")
    void withoutTossOnlyDaumInvestors() {
        Map<String, Object> options = optionsWith(Optional.empty());
        assertThat(options.get("supportedInvestors")).isEqualTo(RadarService.SUPPORTED_INVESTORS);
        assertThat((String) options.get("unsupportedInvestorNote")).contains("TOSS_CLIENT_ID");
        assertThat(options.get("fallbackChain").toString()).contains("토스(공식)");
    }

    @Test
    @DisplayName("신선한 토스 저장본이 있으면 여섯 모두, 한계 설명과 함께")
    void withTossAllInvestors() throws Exception {
        Map<String, Object> options = optionsWith(Optional.of(universe(Instant.now())));
        assertThat(options.get("supportedInvestors")).isEqualTo(RadarService.INVESTORS);
        assertThat((String) options.get("unsupportedInvestorNote")).contains("거래대금 상위 100종목");
    }

    @Test
    @DisplayName("오래된 토스 저장본(26시간 초과)은 없는 것으로 본다")
    void staleTossIsIgnored() throws Exception {
        Map<String, Object> options = optionsWith(Optional.of(universe(Instant.now().minus(30, ChronoUnit.HOURS))));
        assertThat(options.get("supportedInvestors")).isEqualTo(RadarService.SUPPORTED_INVESTORS);
    }
}
