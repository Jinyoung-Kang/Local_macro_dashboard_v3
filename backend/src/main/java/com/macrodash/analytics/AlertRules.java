package com.macrodash.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 알림 사건 판정 — 순수 함수(명세 C1~C3, ADR 0011). 같은 입력이면 늘 같은 사건을 내므로, 평가를 몇 번 해도
 * (사건 키 유일 제약과 함께) 사건은 한 번만 생깁니다.
 */
public final class AlertRules {

    private AlertRules() {
    }

    /** 국면 전환을 "확정"으로 보는 연속 기록 수(사용자 결정, 2026-10-09). 판정 규칙(화면)은 그대로입니다. */
    public static final int REGIME_CONFIRM_DAYS = 3;

    /** 하루의 판정 기록. */
    /**
     * 판정 기록 하루.
     *
     * @param inputStamp 그날 판정에 쓴 입력들의 기준일을 한 문자열로(모르면 null). 직전 기록과 같으면 새 데이터가 없는
     *                   날(주말 등)이라 세지 않습니다(QA-054).
     */
    public record RegimeDay(LocalDate date, String code, String label, String inputStamp) {

        public RegimeDay(LocalDate date, String code, String label) {
            this(date, code, label, null);
        }

        boolean known() {
            return code != null && !"UNKNOWN".equals(code);
        }
    }

    /** 확정된 국면 전환 — {@code confirmedOn}은 새 판정이 {@code confirmDays}번째로 기록된 날. */
    public record RegimeChange(LocalDate confirmedOn, LocalDate startedOn, String fromCode, String fromLabel,
                               String toCode, String toLabel) {
    }

    /**
     * 판정 기록에서 확정된 국면 전환을 모두 찾습니다(오래된 순).
     *
     * <ul>
     *   <li>새 판정이 기록에서 {@code confirmDays}번 연속 이어진 날 한 번 — 하루 이틀 바뀌었다 돌아오는 것은 전환이 아님.</li>
     *   <li>"판정 불가" 날은 세지도 끊지도 않습니다 — 입력 수집이 하루 실패했다고 전환이 생기거나 사라지지 않게.</li>
     *   <li>기록의 첫 확정 국면은 직전을 모르므로 전환이 아닙니다.</li>
     * </ul>
     *
     * @param ascending 날짜 오름차순 기록
     */
    public static List<RegimeChange> confirmedChanges(List<RegimeDay> ascending, int confirmDays) {
        List<RegimeChange> out = new ArrayList<>();
        String confirmed = null;
        String confirmedLabel = null;
        String run = null;
        String runLabel = null;
        LocalDate runStart = null;
        int length = 0;
        String lastStamp = null;
        for (RegimeDay day : ascending) {
            if (!day.known()) {
                continue;
            }
            // 입력 기준일이 직전 기록과 같으면 새 데이터가 없는 날입니다(토·일 기록은 금요일과 같은 입력). 세지도 끊지도
            // 않습니다 — 세면 새 데이터 하루로 '3일 확정'이 됐습니다(결정 2026-10-11, QA-054).
            if (day.inputStamp() != null && day.inputStamp().equals(lastStamp)) {
                continue;
            }
            lastStamp = day.inputStamp();
            if (day.code().equals(run)) {
                length++;
            } else {
                run = day.code();
                runLabel = day.label();
                runStart = day.date();
                length = 1;
            }
            if (length == confirmDays && !run.equals(confirmed)) {
                if (confirmed != null) {
                    out.add(new RegimeChange(day.date(), runStart, confirmed, confirmedLabel, run, runLabel));
                }
                confirmed = run;
                confirmedLabel = runLabel;
            }
        }
        return out;
    }

    /** 하루의 순매수(확정 기록만). 모르면 null. */
    public record DayNet(LocalDate date, Long net) {
    }

    /** 연속 순매수가 정확히 N일이 된 날. */
    public record StreakHit(LocalDate date, int days) {
    }

    /**
     * 연속 순매수(+)가 정확히 {@code n}일에 도달한 날을 모두 찾습니다(오래된 순). 값이 없거나(null) 0이거나 순매도인
     * 날에서 연속이 끊깁니다 — 모르는 날을 이어진 것으로 세지 않습니다. N+1일째 이후는 다시 알리지 않고, 끊긴 뒤 다시
     * N일이 되면 새로 알립니다. 첫 기록부터 이어진 연속은 시작을 모르므로 알리지 않습니다.
     *
     * @param ascending 확정 기록만, 날짜 오름차순
     */
    public static List<StreakHit> streakReached(List<DayNet> ascending, int n) {
        List<StreakHit> out = new ArrayList<>();
        int streak = 0;
        // 첫 기록부터 이어진 연속은 그 앞을 모르므로 시작을 알 수 없습니다 — 창(최근 30거래일 등)이 하루 밀릴 때마다
        // 같은 연속이 다른 날 'N일 연속'으로 새로 잡혔습니다(QA-039). 국면의 '첫 확정은 전환이 아님'과 같은 규칙입니다.
        boolean fromWindowStart = true;
        for (DayNet day : ascending) {
            boolean buying = day.net() != null && day.net() > 0;
            if (!buying) {
                streak = 0;
                fromWindowStart = false;
                continue;
            }
            streak++;
            if (streak == n && !fromWindowStart) {
                out.add(new StreakHit(day.date(), n));
            }
        }
        return out;
    }

    /** 직전 분기에 없던 종목(신규 편입) — 평가액 큰 순. */
    public static List<Holding> newPositions(List<Holding> current, List<Holding> previous) {
        if (previous == null) {
            return List.of();
        }
        List<Holding> out = new ArrayList<>();
        for (HoldingsDiff.Change change : HoldingsDiff.compare(current, previous)) {
            if (HoldingsDiff.NEW.equals(change.action())) {
                out.add(change.holding());
            }
        }
        out.sort((a, b) -> Double.compare(b.value() == null ? 0 : b.value(), a.value() == null ? 0 : a.value()));
        return out;
    }
}
