package com.macrodash.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlertRulesTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 1);

    private static List<AlertRules.RegimeDay> days(String... codes) {
        List<AlertRules.RegimeDay> out = new ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            out.add(new AlertRules.RegimeDay(D.plusDays(i), codes[i], codes[i]));
        }
        return out;
    }

    @Test
    @DisplayName("국면 전환은 새 판정이 3일 이어진 날 한 번 — 하루 이틀 바뀌었다 돌아오면 전환이 아니다")
    void regimeNeedsThreeDays() {
        List<AlertRules.RegimeChange> changes = AlertRules.confirmedChanges(
                days("EXP", "EXP", "EXP", "LATE", "EXP", "LATE", "LATE", "LATE", "LATE"), 3);

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).fromCode()).isEqualTo("EXP");
        assertThat(changes.get(0).toCode()).isEqualTo("LATE");
        assertThat(changes.get(0).confirmedOn()).isEqualTo(D.plusDays(7));
        assertThat(changes.get(0).startedOn()).isEqualTo(D.plusDays(5));
    }

    @Test
    @DisplayName("판정 불가 날은 세지도 끊지도 않고, 첫 확정 국면은 전환이 아니다")
    void unknownDaysAreSkipped() {
        assertThat(AlertRules.confirmedChanges(days("EXP", "EXP", "EXP"), 3)).isEmpty();
        List<AlertRules.RegimeChange> changes = AlertRules.confirmedChanges(
                days("EXP", "EXP", "EXP", "LATE", "UNKNOWN", "LATE", "LATE"), 3);
        assertThat(changes).singleElement().satisfies(c -> assertThat(c.confirmedOn()).isEqualTo(D.plusDays(6)));
    }

    @Test
    @DisplayName("같은 기록이면 몇 번 평가해도 같은 전환 — 사건 키가 같아 한 번만 생긴다")
    void deterministic() {
        List<AlertRules.RegimeDay> history = days("A", "A", "A", "B", "B", "B", "A", "A", "A");
        assertThat(AlertRules.confirmedChanges(history, 3)).isEqualTo(AlertRules.confirmedChanges(history, 3));
        assertThat(AlertRules.confirmedChanges(history, 3)).hasSize(2);
    }

    private static List<AlertRules.DayNet> nets(Long... values) {
        List<AlertRules.DayNet> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            out.add(new AlertRules.DayNet(D.plusDays(i), values[i]));
        }
        return out;
    }

    @Test
    @DisplayName("연속 순매수가 정확히 N일이 된 날만 — N+1일째는 다시 알리지 않고, 끊긴 뒤 다시 N일이면 새로")
    void streakExactlyN() {
        List<AlertRules.StreakHit> hits = AlertRules.streakReached(
                nets(5L, 3L, 9L, 2L, -1L, 4L, 4L, 4L), 3);
        assertThat(hits).extracting(AlertRules.StreakHit::date).containsExactly(D.plusDays(2), D.plusDays(7));
    }

    @Test
    @org.junit.jupiter.api.Disabled("QA-039 재현 — 수정 전에는 창의 첫 기록부터 0으로 세어 창이 밀릴 때마다 새 'N일 연속' 사건을 만든다")
    @DisplayName("QA-039 창이 하루씩 밀려도 같은 연속을 새 사건으로 만들지 않는다 — 첫 기록 앞을 모르면 연속의 시작이 아니다")
    void slidingWindowDoesNotRestartStreak() {
        // 실제 기록: D0 순매도, D1~D6 순매수. 3일 연속은 D3 한 번뿐입니다.
        Long[] full = {-1L, 5L, 5L, 5L, 5L, 5L, 5L};
        assertThat(AlertRules.streakReached(window(full, 0), 3)).extracting(AlertRules.StreakHit::date)
                .containsExactly(D.plusDays(3));
        // 시장 수급은 최근 30거래일만 남아 창의 첫날이 D2·D3·D4로 밀립니다. 그때마다 D4·D5·D6에 '3일 연속'이 새로 생겼습니다.
        for (int start = 2; start <= 4; start++) {
            assertThat(AlertRules.streakReached(window(full, start), 3)).as("창 시작 D%d", start).isEmpty();
        }
    }

    private static List<AlertRules.DayNet> window(Long[] values, int start) {
        List<AlertRules.DayNet> out = new ArrayList<>();
        for (int i = start; i < values.length; i++) {
            out.add(new AlertRules.DayNet(D.plusDays(i), values[i]));
        }
        return out;
    }

    @Test
    @DisplayName("값을 모르는 날(null)과 0인 날은 연속을 끊는다")
    void unknownBreaksStreak() {
        assertThat(AlertRules.streakReached(nets(5L, null, 3L, 2L), 3)).isEmpty();
        assertThat(AlertRules.streakReached(nets(5L, 0L, 3L, 2L), 3)).isEmpty();
    }

    @Test
    @DisplayName("13F 신규 편입은 직전 분기에 없던 종목, 평가액 큰 순. 직전 분기가 없으면 비교하지 않음")
    void newPositions() {
        Holding apple = new Holding("APPLE INC", "037833100", "COM", 100.0, 10.0, 50.0);
        Holding nvidia = new Holding("NVIDIA CORP", "67066G104", "COM", 300.0, 5.0, 30.0);
        Holding small = new Holding("SMALL CO", "000000001", "COM", 10.0, 1.0, 1.0);

        assertThat(AlertRules.newPositions(List.of(apple, nvidia, small), List.of(apple)))
                .extracting(Holding::name).containsExactly("NVIDIA CORP", "SMALL CO");
        assertThat(AlertRules.newPositions(List.of(apple), null)).isEmpty();
    }
}
