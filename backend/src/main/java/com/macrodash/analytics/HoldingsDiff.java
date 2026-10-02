package com.macrodash.analytics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 13F 분기 대비 액션 분류 — 순수 계산.
 *
 * <p>최신 분기와 직전 분기의 보유 항목을 대조해 종목별로 신규 매수 / 전량 매도 /
 * 비중 확대·축소 / 유지를 붙입니다. 화면과 AI 리포트가 같은 분류를 보도록 계산은
 * 한 곳에만 둡니다.
 *
 * <p>직전 분기가 없으면 "비교 데이터 없음"입니다 — 신규 매수로 단정하지 않습니다
 * (수집된 분기가 하나뿐일 수도 있기 때문입니다).
 */
public final class HoldingsDiff {

    /** 비중 변화가 이 값보다 작으면 "유지"로 봅니다(%p). */
    public static final double WEIGHT_EPSILON = 0.05;

    public static final String NO_PREVIOUS = "⚪ 비교 데이터 없음";
    public static final String CANNOT_COMPARE = "⚪ 비교 불가 (주식 수 없음)";
    public static final String NEW = "🆕 신규 매수 (New)";
    public static final String CLOSED = "❌ 전량 매도 (Closed)";
    public static final String ADDED = "📈 비중 확대 (Added)";
    public static final String REDUCED = "📉 비중 축소 (Reduced)";
    public static final String UNCHANGED = "⚪ 유지 (Unchanged)";

    private HoldingsDiff() {
    }

    /**
     * 분류 결과 한 줄.
     *
     * @param holding    이번 분기 항목. 전량 매도 행은 평가액·주식 수·비중이 0인 항목입니다
     * @param action     액션 문구
     * @param weightDiff 비중 변화(%p). 직전 분기가 없으면 null
     * @param sharesDiff 주식 수 변화. 직전 분기가 없으면 null
     */
    public record Change(Holding holding, String action, Double weightDiff, Double sharesDiff) {
    }

    /**
     * 최신 분기와 직전 분기를 대조합니다.
     *
     * @param current  최신 분기 항목
     * @param previous 직전 분기 항목. null이면 직전 분기 없음
     * @return 최신 분기 항목마다 한 줄 + 직전 분기에만 있던 항목의 전량 매도 행. 정렬하지 않습니다
     */
    public static List<Change> compare(List<Holding> current, List<Holding> previous) {
        Map<String, Holding> previousByKey = new LinkedHashMap<>();
        if (previous != null) {
            for (Holding holding : previous) {
                previousByKey.put(key(holding), holding);
            }
        }

        List<Change> rows = new ArrayList<>();
        for (Holding holding : current) {
            if (previous == null) {
                rows.add(new Change(holding, NO_PREVIOUS, null, null));
                continue;
            }
            Holding before = previousByKey.get(key(holding));
            Double prevWeight = before == null ? Double.valueOf(0.0) : before.weight();
            Double prevShares = before == null ? Double.valueOf(0.0) : before.shares();
            Double weightDiff = subtract(holding.weight(), prevWeight);
            Double sharesDiff = subtract(holding.shares(), prevShares);

            // 직전 분기에 **있었는데** 주식 수를 모르면 매매를 판정할 수 없습니다. 0으로
            // 메우면 "신규 매수"로 단정하게 되는데, 그건 데이터가 없다는 사실을 매매 사실로
            // 바꿔 말하는 것입니다.
            boolean cannotCompare = before != null && (prevShares == null || holding.shares() == null);
            String action = cannotCompare ? CANNOT_COMPARE : classify(weightDiff, holding.shares(), prevShares);
            rows.add(new Change(holding, action, weightDiff, sharesDiff));
        }

        // 직전 분기에 있었는데 이번에 사라진 항목 = 전량 매도
        if (previous != null) {
            Set<String> currentKeys = new HashSet<>();
            current.forEach(h -> currentKeys.add(key(h)));
            for (Holding gone : previousByKey.values()) {
                if (currentKeys.contains(key(gone))) {
                    continue;
                }
                Holding closed = new Holding(gone.name(), gone.cusip(), gone.cls(), 0.0, 0.0, 0.0);
                rows.add(new Change(closed, CLOSED, negate(gone.weight()), negate(gone.shares())));
            }
        }
        return rows;
    }

    /**
     * 분기 사이에 같은 보유분을 잇는 키.
     *
     * <p>수집기는 (이름, CUSIP, 종류)로 항목을 나눕니다. 같은 회사가 클래스별로 여러 항목일 수
     * 있어(ALPHABET A·C주, BERKSHIRE A·B주) 이름으로만 키잉하면 마지막 항목이 이기고, A주가
     * C주의 직전 값과 비교돼 손대지 않은 보유분에 가짜 '비중 축소/확대'가 붙었습니다. CUSIP이
     * 가장 정확하고, 없으면 이름+종류입니다.
     */
    static String key(Holding holding) {
        String cusip = holding.cusip() == null ? "" : holding.cusip().trim();
        if (!cusip.isEmpty()) {
            return "cusip:" + cusip;
        }
        return "name:" + holding.name() + "|" + (holding.cls() == null ? "" : holding.cls().trim());
    }

    /** 액션 분류 규칙 (구버전 classify_qoq_action과 동일). */
    public static String classify(Double weightDiff, Double currentShares, Double previousShares) {
        double shares = currentShares == null ? 0.0 : currentShares;
        double before = previousShares == null ? 0.0 : previousShares;
        double diff = weightDiff == null ? 0.0 : weightDiff;

        if (before == 0.0 && shares > 0.0) {
            return NEW;
        }
        if (shares == 0.0 && before > 0.0) {
            return CLOSED;
        }
        if (diff > WEIGHT_EPSILON) {
            return ADDED;
        }
        if (diff < -WEIGHT_EPSILON) {
            return REDUCED;
        }
        return UNCHANGED;
    }

    static Double subtract(Double a, Double b) {
        if (a == null && b == null) {
            return null;
        }
        return (a == null ? 0.0 : a) - (b == null ? 0.0 : b);
    }

    static Double negate(Double value) {
        return value == null ? null : -value;
    }
}
