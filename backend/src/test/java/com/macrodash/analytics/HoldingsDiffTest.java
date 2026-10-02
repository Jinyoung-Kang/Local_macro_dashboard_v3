package com.macrodash.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class HoldingsDiffTest {

    private static Holding h(String name, String cusip, String cls, Double value, Double shares, Double weight) {
        return new Holding(name, cusip, cls, value, shares, weight);
    }

    private static Map<String, HoldingsDiff.Change> byCusip(List<HoldingsDiff.Change> changes) {
        return changes.stream().collect(Collectors.toMap(c -> c.holding().cusip(), Function.identity()));
    }

    @Test
    @DisplayName("같은 이름의 다른 클래스(A주·C주)는 각자 자기 직전 항목과 비교한다")
    void sameIssuerDifferentClassesCompareSeparately() {
        // 수집기는 (이름, CUSIP, 종류)로 항목을 나눕니다. 이름으로만 키잉하면 마지막 항목이 이기고,
        // A주가 C주의 직전 값과 비교돼 손대지 않은 보유분에 가짜 '비중 축소/확대'가 붙었습니다.
        List<Holding> previous = List.of(
                h("ALPHABET INC", "02079K305", "CL A", 1000.0, 100.0, 5.0),
                h("ALPHABET INC", "02079K107", "CL C", 2000.0, 200.0, 10.0));
        List<Holding> current = List.of(
                h("ALPHABET INC", "02079K305", "CL A", 1000.0, 100.0, 5.0),
                h("ALPHABET INC", "02079K107", "CL C", 2000.0, 200.0, 10.0));

        Map<String, HoldingsDiff.Change> changes = byCusip(HoldingsDiff.compare(current, previous));

        assertThat(changes).hasSize(2);
        assertThat(changes.get("02079K305").action()).isEqualTo(HoldingsDiff.UNCHANGED);
        assertThat(changes.get("02079K305").weightDiff()).isEqualTo(0.0);
        assertThat(changes.get("02079K107").action()).isEqualTo(HoldingsDiff.UNCHANGED);
    }

    @Test
    @DisplayName("한 클래스만 정리하면 그 클래스만 전량 매도다")
    void droppingOneClassIsAClosedRowForThatClassOnly() {
        List<Holding> previous = List.of(
                h("ALPHABET INC", "02079K305", "CL A", 1000.0, 100.0, 5.0),
                h("ALPHABET INC", "02079K107", "CL C", 2000.0, 200.0, 10.0));
        List<Holding> current = List.of(
                h("ALPHABET INC", "02079K107", "CL C", 2000.0, 200.0, 10.0));

        Map<String, HoldingsDiff.Change> changes = byCusip(HoldingsDiff.compare(current, previous));

        assertThat(changes.get("02079K305").action()).isEqualTo(HoldingsDiff.CLOSED);
        assertThat(changes.get("02079K305").sharesDiff()).isEqualTo(-100.0);
        assertThat(changes.get("02079K107").action()).isEqualTo(HoldingsDiff.UNCHANGED);
    }

    @Test
    @DisplayName("CUSIP이 없으면 이름+종류로 맞춘다")
    void fallsBackToNameAndClassWithoutCusip() {
        List<Holding> previous = List.of(h("SOME CO", "", "COM", 100.0, 10.0, 1.0));
        List<Holding> current = List.of(h("SOME CO", null, "COM", 120.0, 12.0, 1.2));

        List<HoldingsDiff.Change> changes = HoldingsDiff.compare(current, previous);

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).action()).isEqualTo(HoldingsDiff.ADDED);
    }

    @Test
    @DisplayName("직전 분기에 없던 항목은 주식 수를 몰라도 신규 매수다")
    void absentBeforeIsNewEvenWithoutShares() {
        // 직전 분기에 없었다는 사실은 압니다. 주식 수가 비어 있다고 '비중 확대'로 읽으면
        // 없던 보유분을 늘린 것처럼 말하는 셈입니다.
        List<Holding> previous = List.of(h("COCA COLA CO", "191216100", "COM", 400.0, 50.0, 40.0));
        List<Holding> current = List.of(h("APPLE INC", "037833100", "COM", 600.0, null, 60.0));

        Map<String, HoldingsDiff.Change> changes = byCusip(HoldingsDiff.compare(current, previous));

        assertThat(changes.get("037833100").action()).isEqualTo(HoldingsDiff.NEW);
    }

    @Test
    @DisplayName("비중을 모르면 변화도 모른다 — 0으로 메워 '유지'라고 하지 않는다")
    void unknownWeightGivesUnknownDiff() {
        List<Holding> previous = List.of(h("APPLE INC", "037833100", "COM", 500.0, 100.0, 50.0));
        List<Holding> current = List.of(h("APPLE INC", "037833100", "COM", 600.0, 100.0, null));

        HoldingsDiff.Change change = HoldingsDiff.compare(current, previous).get(0);

        assertThat(change.weightDiff()).isNull();
        assertThat(change.action()).isEqualTo(HoldingsDiff.CANNOT_COMPARE);
    }
}
