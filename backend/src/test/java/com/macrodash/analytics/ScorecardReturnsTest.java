package com.macrodash.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ScorecardReturnsTest {

    @Test
    @DisplayName("0 이하 종가가 낀 구간은 0% 수익률이 아니라 표본에서 빠진다")
    void brokenCloseIsDroppedNotZero() {
        NavigableMap<LocalDate, Double> series = new TreeMap<>();
        LocalDate day = LocalDate.of(2026, 1, 2);
        double[] closes = {100.0, 0.0, 110.0, 121.0};
        for (double close : closes) {
            series.put(day, close);
            day = day.plusDays(1);
        }

        double[] returns = Scorecard.dailyReturns(series);

        // 100→0, 0→110은 깨진 데이터라 뺍니다. 남는 표본은 110→121 하나.
        assertThat(returns).hasSize(1);
        assertThat(returns[0]).isCloseTo(0.1, within(1e-9));
    }
}
