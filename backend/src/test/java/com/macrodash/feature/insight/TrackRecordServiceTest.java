package com.macrodash.feature.insight;

import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import com.macrodash.store.StoreRepository;
import com.macrodash.support.InvalidRequestException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 판정 성과 응답 — 누적 시계열에서 읽고, 표본이 부족하면 수익률 숫자를 내지 않는다.
 */
class TrackRecordServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LocalDate START = LocalDate.of(2025, 1, 1);

    private static List<StoreRepository.TimeseriesPoint> daily(int days, double value) {
        List<StoreRepository.TimeseriesPoint> out = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            out.add(new StoreRepository.TimeseriesPoint(START.plusDays(i), value + i * 0.0001));
        }
        return out;
    }

    private static TrackRecordService service() {
        return service(0);
    }

    /** @param priceOffsetDays 가격 이력이 국면 입력보다 며칠 늦게 시작하는가 */
    private static TrackRecordService service(int priceOffsetDays) {
        StoreRepository repository = mock(StoreRepository.class);
        when(repository.readTimeseries(eq(Datasets.TS_FRED), eq("T10Y3M"), any())).thenReturn(daily(400, 0.5));
        when(repository.readTimeseries(eq(Datasets.TS_FRED), eq("NFCI"), any())).thenReturn(daily(400, -0.4));
        when(repository.readTimeseries(eq(Datasets.TS_FRED), eq("BAMLH0A0HYM2"), any())).thenReturn(daily(400, 3.0));
        when(repository.readTimeseries(eq(Datasets.TS_LIQUIDITY), eq("netLiquidityM"), any()))
                .thenReturn(daily(400, 6_000_000.0).stream()
                        .map(p -> new StoreRepository.TimeseriesPoint(p.date(),
                                6_000_000.0 + p.date().toEpochDay() % 1000 * 10.0)).toList());

        ObjectNode payload = MAPPER.createObjectNode();
        ObjectNode spy = payload.putObject("tickers").putObject("SPY");
        ArrayNode dates = spy.putArray("dates");
        ArrayNode closes = spy.putArray("close");
        for (int i = priceOffsetDays; i < 420; i++) {
            dates.add(START.plusDays(i).toString());
            closes.add(100.0 + i * 0.1);
        }
        StoreReader store = mock(StoreReader.class);
        when(store.read(anyString(), anyLong(), anyString())).thenReturn(Optional.empty());
        when(store.read(eq(Datasets.SNAP_EQUITY_HISTORY), anyLong(), anyString())).thenReturn(Optional.of(
                new Snapshot(Datasets.SNAP_EQUITY_HISTORY, payload, "json", "ok", null, Instant.now())));
        return new TrackRecordService(repository, store);
    }

    @Test
    @DisplayName("국면별 4·13·26주 성과와 비교 기준을 내고, 표본 부족이면 수익률 대신 표본 수만 낸다")
    @SuppressWarnings("unchecked")
    void buildsTrackRecordAndHidesThinSamples() {
        Map<String, Object> out = service().trackRecord("SPY", true);

        assertThat(out.get("available")).isEqualTo(true);
        assertThat((List<Object>) out.get("limits")).hasSize(5);
        List<Map<String, Object>> all = (List<Map<String, Object>>) out.get("all");
        assertThat(all).extracting(m -> m.get("code"))
                .containsExactly("EXPANSION", "LATE", "RECOVERY", "CONTRACTION");
        List<Map<String, Object>> baseline = (List<Map<String, Object>>) out.get("baseline");
        assertThat(baseline).extracting(m -> m.get("horizonWeeks")).containsExactly(4, 13, 26);

        List<Map<String, Object>> start = (List<Map<String, Object>>) out.get("start");
        for (Map<String, Object> regime : start) {
            for (Map<String, Object> cell : (List<Map<String, Object>>) regime.get("cells")) {
                if (!Boolean.TRUE.equals(cell.get("sufficient"))) {
                    assertThat(cell.get("mean")).as("표본 부족이면 숫자를 내지 않음").isNull();
                    assertThat(cell.get("count")).isNotNull();
                }
            }
        }
        assertThat(LocalDate.parse(String.valueOf(out.get("evaluatedFrom"))).getDayOfWeek())
                .as("판정은 목요일").isEqualTo(java.time.DayOfWeek.THURSDAY);
    }

    @Test
    @DisplayName("모르는 자산은 400 — 아무 문자열이나 캐시 키가 되지 않게")
    void unknownAssetIsRejected() {
        assertThatThrownBy(() -> service().trackRecord("TSLA", true)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("가격 저장본이 없으면 어떤 태스크를 돌릴지 알려 준다")
    void missingPricesExplainWhatToRun() {
        Map<String, Object> out = service().trackRecord("GLD", true);
        assertThat(out.get("available")).isEqualTo(false);
        assertThat(String.valueOf(out.get("message"))).contains("sector_history");
    }

    @Test
    @DisplayName("무료 등급은 SPY만 — 다른 자산은 계산하지 않고 잠금 안내")
    void freeTierSeesSpyOnly() {
        Map<String, Object> locked = service().trackRecord("QQQ", false);
        assertThat(locked.get("available")).isEqualTo(false);
        assertThat(locked.get("locked")).isEqualTo(true);
        assertThat(service().trackRecord("SPY", false).get("available")).isEqualTo(true);
    }

    @Test
    @DisplayName("QA-042 가격 이력이 국면 입력보다 늦게 시작하면 그 시작일을 한계에 적는다 — IEF·GLD는 2년치뿐")
    @SuppressWarnings("unchecked")
    void shortPriceHistoryIsStatedInLimits() {
        Map<String, Object> out = service(200).trackRecord("SPY", true);

        String priceFrom = START.plusDays(200).toString();
        assertThat((List<String>) out.get("limits")).anySatisfy(line -> assertThat(line).contains(priceFrom));
    }

    @Test
    @Disabled("QA-063: 고정 문구 '약 3년'이 실제 기간 줄과 함께 남음 — 수정 커밋에서 켬")
    @DisplayName("QA-063 재현 기간은 한 줄, 실제 입력·가격 시작일에서 만든다 — 고정 문구 '약 3년'과 어긋나지 않게")
    @SuppressWarnings("unchecked")
    void reproducedPeriodIsOneComputedLine() {
        for (int offset : new int[]{0, 200}) {
            List<String> limits = (List<String>) service(offset).trackRecord("SPY", true).get("limits");

            assertThat(limits).noneSatisfy(line -> assertThat(line).contains("약 3년"));
            assertThat(limits).filteredOn(line -> line.contains("재현 기간")).hasSize(1);
        }
        List<String> full = (List<String>) service(0).trackRecord("SPY", true).get("limits");
        assertThat(full).anySatisfy(line -> assertThat(line).contains("하이일드").contains(START.toString()));
    }
}
