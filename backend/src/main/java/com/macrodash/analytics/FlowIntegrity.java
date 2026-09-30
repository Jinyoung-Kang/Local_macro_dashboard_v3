package com.macrodash.analytics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 토스증권 투자자별 매매대금의 내부 정합성.
 *
 * <p>토스 스펙이 약속한 두 등식을 저장본으로 확인합니다.
 * <ol>
 *   <li>4개 분류(개인·외국인·기관·기타법인)의 매수 합계 = 매도 합계 (시장 전체 기준)</li>
 *   <li>기관 합계 = 기관 세부 7개 항목의 합 (매수·매도 각각)</li>
 * </ol>
 *
 * <p>둘 중 하나가 깨지면 토스가 틀렸다기보다 <b>우리 정규화 코드가 값을 엉뚱한 칸에
 * 넣었거나 단위를 잘못 읽은 것</b>일 가능성이 큽니다. 다른 출처가 필요 없는 대조라
 * 장 시간 게이트가 없고, 대신 값이 모두 채워진 확정 기록(당일 잠정치 제외)만 봅니다.
 */
public final class FlowIntegrity {

    private FlowIntegrity() {
    }

    public static final String NAME = "토스 투자자별 매매대금 정합성 (매수 합계 = 매도 합계)";
    /** 스펙상 정확히 같아야 하지만, 반올림 여지를 조금 둡니다. */
    public static final double TOLERANCE_PCT = 0.1;

    static final List<String> INVESTORS = List.of("individual", "foreigner", "institution", "otherCorporation");
    static final List<String> BREAKDOWN = List.of(
            "pensionFund", "financialInvestment", "trust", "privateEquityFund",
            "insurance", "bank", "otherFinancialInstitution");

    /**
     * 시장별 기록(최신순)의 정합성을 판정합니다.
     *
     * @param recordsByMarket {@code KOSPI|KOSDAQ → 정규화된 기록 목록(최신순)}
     * @return 판정 결과. 확정 기록이 하나도 없으면 "확인 못 함"
     */
    public static Verification.Result check(Map<String, List<FlowRecord>> recordsByMarket) {
        List<Verification.Reading> readings = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<String> dates = new ArrayList<>();
        double worst = 0.0;

        for (Map.Entry<String, List<FlowRecord>> entry : recordsByMarket.entrySet()) {
            FlowRecord record = latestComplete(entry.getValue());
            if (record == null) {
                continue;
            }
            String label = "KOSDAQ".equals(entry.getKey()) ? "코스닥" : "코스피";
            String date = record.date();
            dates.add(label + " " + date);

            long buy = sum(record, "investors", INVESTORS, "buy");
            long sell = sum(record, "investors", INVESTORS, "sell");
            readings.add(Verification.Reading.dated(label + " 매수 합계 (억 원)", buy / 1e8,
                    "4개 분류 · 기준일 " + date, date));
            readings.add(Verification.Reading.dated(label + " 매도 합계 (억 원)", sell / 1e8,
                    "4개 분류 · 기준일 " + date, date));

            double gap = gapPct(buy, sell);
            worst = Math.max(worst, gap);
            if (gap > TOLERANCE_PCT) {
                problems.add("%s 매수·매도 합계가 %.3f%% 다릅니다".formatted(label, gap));
            }

            for (String side : List.of("buy", "sell")) {
                Long total = value(record, "investors", "institution", side);
                Long parts = sumOrNull(record, "breakdown", BREAKDOWN, side);
                if (total == null || parts == null) {
                    continue;  // 세부가 없는 기록은 이 등식을 확인하지 않습니다
                }
                double partGap = gapPct(total, parts);
                worst = Math.max(worst, partGap);
                if (partGap > TOLERANCE_PCT) {
                    problems.add("%s 기관 합계와 세부 7개 합이 %.3f%% 다릅니다 (%s)"
                            .formatted(label, partGap, "buy".equals(side) ? "매수" : "매도"));
                }
            }
        }

        if (readings.isEmpty()) {
            return Verification.skipped(NAME,
                    "네 분류 값이 모두 채워진 확정 기록이 없습니다. 당일 잠정치에는 일부 값이 비어 있습니다 — "
                            + "toss_market_flows가 확정치를 받은 뒤(당일 저녁) 다시 확인하세요.");
        }
        if (problems.isEmpty()) {
            return new Verification.Result(NAME, Verification.MATCH, readings, TOLERANCE_PCT, worst,
                    "스펙대로 매수 합계와 매도 합계가 같고, 기관 합계가 세부 7개 합과 같습니다 (%s)."
                            .formatted(String.join(", ", dates)));
        }
        return new Verification.Result(NAME, Verification.MISMATCH, readings, TOLERANCE_PCT, worst,
                String.join(" · ", problems)
                        + ". 토스가 약속한 등식이 깨졌으므로 정규화 코드(buyAmount/sellAmount 매핑)를 먼저 확인하세요.");
    }

    /** 네 분류의 매수·매도가 모두 있는 가장 최신 기록. 없으면 null. */
    static FlowRecord latestComplete(List<FlowRecord> records) {
        if (records == null) {
            return null;
        }
        for (FlowRecord record : records) {
            boolean complete = INVESTORS.stream().allMatch(key ->
                    value(record, "investors", key, "buy") != null
                            && value(record, "investors", key, "sell") != null);
            if (complete) {
                return record;
            }
        }
        return null;
    }

    static Long value(FlowRecord record, String group, String key, String side) {
        return record.value(group, key, side);
    }

    private static long sum(FlowRecord record, String group, List<String> keys, String side) {
        long total = 0;
        for (String key : keys) {
            Long v = value(record, group, key, side);
            total += v == null ? 0 : v;
        }
        return total;
    }

    /** 하나라도 비어 있으면 null — 빈 값을 0으로 메워 등식을 "통과"시키지 않습니다. */
    private static Long sumOrNull(FlowRecord record, String group, List<String> keys, String side) {
        long total = 0;
        for (String key : keys) {
            Long v = value(record, group, key, side);
            if (v == null) {
                return null;
            }
            total += v;
        }
        return total;
    }

    private static double gapPct(long a, long b) {
        long base = Math.max(Math.abs(Math.min(a, b)), 1);
        return Math.abs(a - b) * 100.0 / base;
    }
}
