package com.macrodash.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * COT 백테스트의 수익률 시작점 — QA-012, QA-013.
 *
 * <p>QA-012: COT 기준일은 화요일이고 공시는 금요일입니다. 화요일 종가부터 수익률을 재면, 공시 전
 * 사흘(수·목·금)의 움직임을 "신호를 보고 들어간 결과"로 셉니다 — 미래 정보입니다.
 *
 * <p>QA-013: 가격 이력이 시작되기 전의 신호는 첫 가격일로 당겨 계산됐습니다(`ceilingEntry`).
 * 2024-08의 신호가 2024-10-09 종가에서 출발한 수익률로 집계됩니다.
 */
class CotBacktestQaTest {

    /** 2020-01-07(화)부터 주간 60개. 마지막 하나만 극단 롱. */
    private static List<LocalDate> tuesdays() {
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            dates.add(LocalDate.of(2020, 1, 7).plusWeeks(i));
        }
        return dates;
    }

    private static List<Double> netWithLastExtreme() {
        List<Double> net = new ArrayList<>();
        for (int i = 0; i < 59; i++) {
            net.add(100.0 - i);   // 줄곧 내려가 앞쪽에는 극단 롱이 없음
        }
        net.add(10_000.0);
        return net;
    }

    @Test
    @DisplayName("QA-012: 수익률은 공시(금) 다음 거래일 종가에서 출발한다")
    void entryAfterRelease() {
        List<LocalDate> dates = tuesdays();
        LocalDate asOf = dates.get(59);                        // 2021-02-23(화)
        TreeMap<LocalDate, Double> prices = new TreeMap<>();
        prices.put(asOf, 100.0);                               // 화: 기준일
        prices.put(asOf.plusDays(1), 150.0);                   // 수~금: 공시 전에 이미 오름
        prices.put(asOf.plusDays(3), 150.0);                   // 금: 공시일
        prices.put(asOf.plusDays(6), 150.0);                   // 다음 월: 진입
        prices.put(asOf.plusDays(28), 160.0);
        prices.put(asOf.plusDays(6 + 28), 165.0);              // 진입 4주 뒤

        List<CotExtremes.Event> events =
                CotExtremes.findEvents(dates, netWithLastExtreme(), prices, 20, 95, 5, 4);

        CotExtremes.Event last = events.get(events.size() - 1);
        assertThat(last.date()).isEqualTo(asOf);
        assertThat(last.forwardReturns().get("4w"))
                .as("150 → 165. 화요일 100에서 재면 +60%%로 부풀려짐")
                .isEqualTo(10.0, within(1e-9));
    }

    @Test
    @DisplayName("QA-013: 가격 이력이 시작되기 전의 신호는 수익률을 모른다(null)")
    void signalBeforePriceHistoryIsUnknown() {
        TreeMap<LocalDate, Double> prices = new TreeMap<>();
        LocalDate first = LocalDate.of(2024, 10, 9);
        for (int i = 0; i < 200; i++) {
            prices.put(first.plusDays(i), 100.0 + i);
        }

        assertThat(ForwardReturns.forwardReturn(prices, LocalDate.of(2024, 8, 13), 13))
                .as("8월 신호를 10-09 종가에서 출발시키면 안 됨")
                .isNull();
    }
}
