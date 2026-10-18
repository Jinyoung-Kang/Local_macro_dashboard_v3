package com.macrodash.feature.institution;

import com.macrodash.read.ComputedCache;
import com.macrodash.store.StoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 13F 포트폴리오도 같은 저장본이면 다시 계산하지 않는다 — 교집합·신규 편입·위험 분석과 같은 응답 캐시.
 *
 * <p>QA-112·121로 비중 추이가 8분기 전체 보유를 증권 단위로 집계하게 되면서 요청 하나의 계산이 늘었습니다
 * (QA 스택 측정: 캐시 없는 첫 요청 13→22ms, 동시 50명 p95 9→28ms). 이 경로만 캐시를 거치지 않아
 * 부하 중 다른 요청까지 느려졌습니다.
 */
class InstitutionControllerCacheTest {

    @Test
    @DisplayName("같은 기관·조건의 포트폴리오는 저장본이 그대로면 한 번만 계산한다")
    void portfolioIsComputedOncePerSnapshotVersion() {
        StoreRepository repository = mock(StoreRepository.class);
        when(repository.latestCollectedAt()).thenReturn(Instant.parse("2026-10-12T06:43:12Z"));
        Sec13FService sec13f = mock(Sec13FService.class);
        when(sec13f.portfolio("0001608046", 8, 30)).thenReturn(Map.of("available", true));
        InstitutionController controller = new InstitutionController(sec13f, mock(GuruService.class), new ComputedCache(repository));

        Map<String, Object> first = controller.portfolio("0001608046", 8, 30);
        Map<String, Object> second = controller.portfolio("0001608046", 8, 30);

        assertThat(second).isSameAs(first);
        verify(sec13f, times(1)).portfolio("0001608046", 8, 30);
        // 조건이 다르면 따로 계산합니다.
        controller.portfolio("0001608046", 4, 30);
        verify(sec13f, times(1)).portfolio("0001608046", 4, 30);
    }
}
