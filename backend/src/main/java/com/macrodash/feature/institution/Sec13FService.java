package com.macrodash.feature.institution;

import com.macrodash.support.Json;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import com.macrodash.store.StoreReader;
import com.macrodash.support.InvalidRequestException;
import com.macrodash.support.Params;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 📑 기관 13F 포트폴리오 · 🎯 13F Money 교집합.
 *
 * <p>SEC 13F는 분기 공시이고 <b>45일 지연</b>입니다. 지금 시장 포지션이 아니라
 * 지난 분기말 스냅샷이라는 점을 화면이 계속 상기시켜야 합니다.
 *
 * <p>분기 대비 액션(신규 매수 / 전량 매도 / 비중 확대·축소 / 유지) 분류와 여러
 * 기관의 교집합 집계를 여기서 계산합니다. 구버전은 화면 코드가 계산해서,
 * 같은 값을 AI 리포트가 다르게 말할 여지가 있었습니다.
 */
@Service
public class Sec13FService {

    /** 비중 변화가 이 값보다 작으면 "유지"로 봅니다(%p). */
    private static final double WEIGHT_EPSILON = 0.05;

    public static final List<Map<String, String>> INSTITUTIONS = institutions();

    private final StoreReader store;

    public Sec13FService(StoreReader store) {
        this.store = store;
    }

    public Map<String, Object> institutionList() {
        return Map.of("institutions", INSTITUTIONS);
    }

    /**
     * 추적하는 기관의 CIK인지 확인합니다.
     *
     * <p>모르는 CIK는 저장본이 있을 수 없는데, 예전에는 그대로 저장본을 찾다가 "없음" →
     * 13F 전체 수집을 동기로 기다렸습니다(재현: CIK 하나에 수집 2회, 세 개면 6회·30초).
     *
     * @throws InvalidRequestException 목록에 없는 CIK
     */
    public static void requireKnownCik(String cik) {
        boolean known = INSTITUTIONS.stream().anyMatch(entry -> entry.get("cik").equals(cik));
        if (!known) {
            throw new InvalidRequestException("추적하지 않는 기관 CIK입니다: " + Params.echo(cik)
                    + " (/api/sec13f/institutions 목록의 cik를 쓰세요)");
        }
    }

    /** 기관 1곳의 분기 이력 + 최신 분기 QoQ 분석. */
    public Map<String, Object> portfolio(String cik, int quarters, int topN) {
        requireKnownCik(cik);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cik", cik);
        out.put("quartersRequested", quarters);

        // quarters는 URL 파라미터입니다. 0·음수·9999 같은 값이 그대로 들어오면
        //   - 0 이하: subList(0, 0)이 빈 목록이 되어 뒤의 get(0)에서 500
        //   - 저장 한도 초과: 존재하지 않는 데이터셋 이름(q999)을 찾아 "없음" 응답
        // 이 됩니다. 우리가 보관하는 범위로 먼저 접어 둡니다.
        int wanted = Math.min(Math.max(1, quarters), Datasets.MAX_TRACKED_QUARTERS);
        out.put("quartersUsed", wanted);

        Optional<Snapshot> snapshot = readHistory(cik, wanted);
        if (snapshot.isEmpty() || snapshot.get().payload() == null) {
            out.put("available", false);
            out.put("quarters", List.of());
            out.put("message", "13F 저장본이 없습니다. 수집기의 weekly 작업을 실행하세요.");
            return out;
        }

        JsonNode payload = snapshot.get().payload();
        List<JsonNode> allQuarters = dedupeByReportDate(Json.array(payload, "quarters"));
        if (allQuarters.isEmpty()) {
            out.put("available", false);
            out.put("quarters", List.of());
            out.put("error", Json.asText(payload, "error"));
            return out;
        }

        List<JsonNode> selected = allQuarters.size() > wanted
                ? allQuarters.subList(0, wanted)
                : allQuarters;

        out.put("available", true);
        snapshot.get().putFreshness(out);
        out.put("error", Json.asText(payload, "error"));
        out.put("institution", institutionByCik(cik));

        List<Map<String, Object>> quarterSummaries = new ArrayList<>();
        for (JsonNode quarter : selected) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("filingDate", Json.asText(quarter, "filingDate"));
            summary.put("reportDate", Json.asText(quarter, "reportDate"));
            summary.put("totalValue", Json.asDouble(quarter, "totalValue"));
            summary.put("holdingCount", Json.array(quarter, "holdings").size());
            quarterSummaries.add(summary);
        }
        out.put("quarters", quarterSummaries);

        JsonNode latest = selected.get(0);
        JsonNode previous = selected.size() > 1 ? selected.get(1) : null;

        out.put("latest", Map.of(
                "reportDate", String.valueOf(Json.asText(latest, "reportDate")),
                "filingDate", String.valueOf(Json.asText(latest, "filingDate")),
                "totalValue", Json.asDouble(latest, "totalValue")));
        out.put("holdings", compareQuarters(latest, previous, topN));
        out.put("weightHistory", weightHistory(selected, topN));
        return out;
    }

    /**
     * 같은 분기(reportDate)가 두 번 들어온 경우 하나만 남깁니다.
     *
     * <p>SEC에는 정정 공시(13F-HR/A)가 있습니다. 원본과 정정본이 같은 분기를
     * 가리키므로 목록에 같은 reportDate가 두 번 나타나고, 화면에서는 히트맵에
     * 빈 열이 하나 더 생기고 "수집된 분기"에도 같은 날짜가 두 번 찍힙니다.
     * 목록은 최신순이므로 <b>먼저 나온 것(더 최근 제출)</b>을 남깁니다.
     */
    static List<JsonNode> dedupeByReportDate(List<JsonNode> quarters) {
        Map<String, JsonNode> byDate = new LinkedHashMap<>();
        for (JsonNode quarter : quarters) {
            String reportDate = Json.asText(quarter, "reportDate");
            if (reportDate == null) {
                continue;
            }
            byDate.putIfAbsent(reportDate, quarter);
        }
        return new ArrayList<>(byDate.values());
    }

    /**
     * 최신 분기와 직전 분기를 대조해 종목별 액션을 분류합니다.
     *
     * <p>직전 분기가 없으면 "비교 데이터 없음"입니다 — 신규 매수로 단정하지
     * 않습니다(수집된 분기가 하나뿐일 수도 있기 때문입니다).
     */
    List<Map<String, Object>> compareQuarters(JsonNode current, JsonNode previous, int topN) {
        Map<String, JsonNode> previousByName = new LinkedHashMap<>();
        if (previous != null) {
            for (JsonNode holding : Json.array(previous, "holdings")) {
                previousByName.put(Json.asText(holding, "name"), holding);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        List<JsonNode> holdings = Json.array(current, "holdings");

        for (JsonNode holding : holdings) {
            String name = Json.asText(holding, "name");
            Double weight = Json.asDouble(holding, "weight");
            Double shares = Json.asDouble(holding, "shares");

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            // CUSIP은 숫자로만 이루어질 수 있어 반드시 문자열로 다룹니다.
            row.put("cusip", Json.asText(holding, "cusip"));
            row.put("class", Json.asText(holding, "class"));
            row.put("value", Json.asDouble(holding, "value"));
            row.put("shares", shares);
            row.put("weight", weight);

            if (previous == null) {
                row.put("action", "⚪ 비교 데이터 없음");
                row.put("weightDiff", null);
                row.put("sharesDiff", null);
            } else {
                JsonNode before = previousByName.get(name);
                // ⚠️ Double.valueOf가 꼭 필요합니다. 한쪽이 primitive 0.0이면
                // 삼항식 전체가 double로 승격되어 반대편 Double이 자동 언박싱되고,
                // 값이 없을 때 NullPointerException으로 500이 납니다.
                // 13F 공시에는 shares가 빠진 보유 항목이 실제로 있습니다.
                Double prevWeight = before == null
                        ? Double.valueOf(0.0) : Json.asDouble(before, "weight");
                Double prevShares = before == null
                        ? Double.valueOf(0.0) : Json.asDouble(before, "shares");
                Double weightDiff = subtract(weight, prevWeight);
                row.put("weightDiff", weightDiff);
                row.put("sharesDiff", subtract(shares, prevShares));

                // 직전 분기에 **있었는데** 주식 수를 모르면 매매를 판정할 수
                // 없습니다. 0으로 메우면 "신규 매수"로 단정하게 되는데, 그건
                // 데이터가 없다는 사실을 매매 사실로 바꿔 말하는 것입니다.
                boolean cannotCompare = before != null && (prevShares == null || shares == null);
                row.put("action", cannotCompare
                        ? "⚪ 비교 불가 (주식 수 없음)"
                        : classify(weightDiff, shares, prevShares));
            }
            rows.add(row);
        }

        // 직전 분기에 있었는데 이번에 사라진 종목 = 전량 매도
        if (previous != null) {
            Set<String> currentNames = new LinkedHashSet<>();
            holdings.forEach(h -> currentNames.add(Json.asText(h, "name")));

            for (Map.Entry<String, JsonNode> entry : previousByName.entrySet()) {
                if (currentNames.contains(entry.getKey())) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", entry.getKey());
                row.put("cusip", Json.asText(entry.getValue(), "cusip"));
                row.put("class", Json.asText(entry.getValue(), "class"));
                row.put("value", 0.0);
                row.put("shares", 0.0);
                row.put("weight", 0.0);
                row.put("weightDiff", negate(Json.asDouble(entry.getValue(), "weight")));
                row.put("sharesDiff", negate(Json.asDouble(entry.getValue(), "shares")));
                row.put("action", "❌ 전량 매도 (Closed)");
                rows.add(row);
            }
        }

        rows.sort(Comparator.comparingDouble(
                (Map<String, Object> row) -> row.get("value") instanceof Double d ? d : 0.0
        ).reversed());

        return topN > 0 && rows.size() > topN ? rows.subList(0, topN) : rows;
    }

    /** 액션 분류 규칙 (구버전 classify_qoq_action과 동일). */
    static String classify(Double weightDiff, Double currentShares, Double previousShares) {
        double shares = currentShares == null ? 0.0 : currentShares;
        double before = previousShares == null ? 0.0 : previousShares;
        double diff = weightDiff == null ? 0.0 : weightDiff;

        if (before == 0.0 && shares > 0.0) {
            return "🆕 신규 매수 (New)";
        }
        if (shares == 0.0 && before > 0.0) {
            return "❌ 전량 매도 (Closed)";
        }
        if (diff > WEIGHT_EPSILON) {
            return "📈 비중 확대 (Added)";
        }
        if (diff < -WEIGHT_EPSILON) {
            return "📉 비중 축소 (Reduced)";
        }
        return "⚪ 유지 (Unchanged)";
    }

    /** 상위 종목의 분기별 비중 추이 (차트용). */
    private Map<String, Object> weightHistory(List<JsonNode> quarters, int topN) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (quarters.isEmpty()) {
            return out;
        }

        List<String> names = new ArrayList<>();
        for (JsonNode holding : Json.array(quarters.get(0), "holdings")) {
            names.add(Json.asText(holding, "name"));
            if (names.size() >= Math.max(1, Math.min(topN, 15))) {
                break;
            }
        }

        List<String> dates = new ArrayList<>();
        // 최신이 앞이므로 차트용으로 과거 → 최신 순서로 뒤집습니다.
        List<JsonNode> ordered = new ArrayList<>(quarters);
        java.util.Collections.reverse(ordered);
        ordered.forEach(q -> dates.add(Json.asText(q, "reportDate")));

        Map<String, List<Double>> series = new LinkedHashMap<>();
        for (String name : names) {
            List<Double> values = new ArrayList<>();
            for (JsonNode quarter : ordered) {
                Double weight = null;
                for (JsonNode holding : Json.array(quarter, "holdings")) {
                    if (name.equals(Json.asText(holding, "name"))) {
                        weight = Json.asDouble(holding, "weight");
                        break;
                    }
                }
                // 보유하지 않은 분기는 0%가 맞습니다(데이터 없음이 아니라 미보유).
                values.add(weight == null ? 0.0 : weight);
            }
            series.put(name, values);
        }

        out.put("dates", dates);
        out.put("series", series);
        return out;
    }

    /**
     * 🎯 여러 기관의 교집합.
     *
     * @param ciks       비교할 기관 CIK
     * @param reportDate 기준 분기(없으면 각 기관의 최신 분기)
     * @param minHolders 최소 공통 보유 기관 수
     */
    public Map<String, Object> consensus(List<String> ciks, String reportDate,
                                         int minHolders, int topN) {
        ciks.forEach(Sec13FService::requireKnownCik);   // 저장본을 찾기 전에 전부 확인합니다
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Map<String, Object>> aggregate = new LinkedHashMap<>();
        List<String> participants = new ArrayList<>();
        Set<String> availableDates = new LinkedHashSet<>();

        for (String cik : ciks) {
            Optional<Snapshot> snapshot = readHistory(cik, Datasets.MAX_TRACKED_QUARTERS);
            if (snapshot.isEmpty() || snapshot.get().payload() == null) {
                continue;
            }

            List<JsonNode> quarters = dedupeByReportDate(
                    Json.array(snapshot.get().payload(), "quarters"));
            if (quarters.isEmpty()) {
                continue;
            }
            quarters.forEach(q -> availableDates.add(Json.asText(q, "reportDate")));

            int index = 0;
            if (reportDate != null && !reportDate.isBlank()) {
                index = -1;
                for (int i = 0; i < quarters.size(); i++) {
                    if (reportDate.equals(Json.asText(quarters.get(i), "reportDate"))) {
                        index = i;
                        break;
                    }
                }
                if (index < 0) {
                    continue;    // 그 기관은 해당 분기를 공시하지 않았습니다
                }
            }

            JsonNode current = quarters.get(index);
            JsonNode previous = index + 1 < quarters.size() ? quarters.get(index + 1) : null;
            String institution = institutionByCik(cik).getOrDefault("name", cik);
            participants.add(institution);

            for (Map<String, Object> holding : compareQuarters(current, previous, 100)) {
                String name = String.valueOf(holding.get("name"));
                Map<String, Object> entry = aggregate.computeIfAbsent(name, key -> {
                    Map<String, Object> fresh = new LinkedHashMap<>();
                    fresh.put("name", key);
                    fresh.put("cusip", holding.get("cusip"));
                    fresh.put("holders", new ArrayList<String>());
                    fresh.put("actions", new ArrayList<String>());
                    fresh.put("totalValue", 0.0);
                    fresh.put("weightSum", 0.0);
                    fresh.put("maxWeight", 0.0);
                    return fresh;
                });

                @SuppressWarnings("unchecked")
                List<String> holders = (List<String>) entry.get("holders");
                @SuppressWarnings("unchecked")
                List<String> actions = (List<String>) entry.get("actions");

                holders.add(institution);
                actions.add(String.valueOf(holding.get("action")));

                double value = holding.get("value") instanceof Double d ? d : 0.0;
                double weight = holding.get("weight") instanceof Double w ? w : 0.0;
                entry.put("totalValue", (double) entry.get("totalValue") + value);
                entry.put("weightSum", (double) entry.get("weightSum") + weight);
                entry.put("maxWeight", Math.max((double) entry.get("maxWeight"), weight));
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> entry : aggregate.values()) {
            @SuppressWarnings("unchecked")
            List<String> holders = (List<String>) entry.get("holders");
            @SuppressWarnings("unchecked")
            List<String> actions = (List<String>) entry.get("actions");

            if (holders.size() < minHolders) {
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>(entry);
            row.put("holderCount", holders.size());
            row.put("avgWeight", (double) entry.get("weightSum") / holders.size());
            row.put("buyCount", actions.stream()
                    .filter(a -> a.contains("신규 매수") || a.contains("비중 확대")).count());
            row.put("sellCount", actions.stream()
                    .filter(a -> a.contains("전량 매도") || a.contains("비중 축소")).count());
            row.remove("weightSum");
            rows.add(row);
        }

        rows.sort(Comparator
                .comparingInt((Map<String, Object> row) -> (int) row.get("holderCount"))
                .thenComparingDouble(row -> (double) row.get("totalValue"))
                .reversed());

        out.put("participants", participants);
        out.put("participantCount", participants.size());
        out.put("availableDates", availableDates.stream().sorted(Comparator.reverseOrder()).toList());
        out.put("reportDate", reportDate);
        out.put("minHolders", minHolders);
        out.put("rows", topN > 0 && rows.size() > topN ? rows.subList(0, topN) : rows);
        out.put("available", !rows.isEmpty());
        return out;
    }

    /**
     * 🆕 이번 분기에 <b>여러 기관이 함께 새로 담은</b> 종목.
     *
     * <p>교집합 화면은 "지금 누가 무엇을 들고 있는가"를 보여 줍니다. 그런데 더
     * 신호에 가까운 것은 <b>이번 분기에 새로 들어온</b> 종목입니다 — 한 곳이
     * 새로 사면 취향이지만, 여러 곳이 같은 분기에 새로 사면 테마입니다.
     *
     * <p>"신규 매수"는 {@link #compareQuarters}가 붙인 액션을 그대로 씁니다.
     * 직전 분기와 비교할 수 없는 경우(주식 수 누락 등)는 <b>세지 않습니다</b> —
     * 모르는 것을 신규 매수로 올리면 없던 테마가 생깁니다.
     *
     * @param minHolders 최소 몇 곳이 새로 담았을 때 목록에 올릴지
     */
    public Map<String, Object> newBuys(List<String> ciks, String reportDate, int minHolders) {
        ciks.forEach(Sec13FService::requireKnownCik);   // 저장본을 찾기 전에 전부 확인합니다
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Map<String, Object>> aggregate = new LinkedHashMap<>();
        List<String> participants = new ArrayList<>();
        Set<String> availableDates = new LinkedHashSet<>();

        for (String cik : ciks) {
            Optional<Snapshot> snapshot = readHistory(cik, Datasets.MAX_TRACKED_QUARTERS);
            if (snapshot.isEmpty() || snapshot.get().payload() == null) {
                continue;
            }
            List<JsonNode> quarters = dedupeByReportDate(
                    Json.array(snapshot.get().payload(), "quarters"));
            if (quarters.size() < 2) {
                // 비교할 직전 분기가 없으면 신규 매수를 판정할 수 없습니다.
                continue;
            }
            quarters.forEach(q -> availableDates.add(Json.asText(q, "reportDate")));

            int index = 0;
            if (reportDate != null && !reportDate.isBlank()) {
                index = -1;
                for (int i = 0; i < quarters.size() - 1; i++) {
                    if (reportDate.equals(Json.asText(quarters.get(i), "reportDate"))) {
                        index = i;
                        break;
                    }
                }
                if (index < 0) {
                    continue;
                }
            }

            JsonNode current = quarters.get(index);
            JsonNode previous = quarters.get(index + 1);
            String institution = institutionByCik(cik).getOrDefault("name", cik);
            participants.add(institution);

            for (Map<String, Object> holding : compareQuarters(current, previous, 200)) {
                String action = String.valueOf(holding.get("action"));
                if (!action.contains("신규 매수")) {
                    continue;
                }
                String name = String.valueOf(holding.get("name"));
                Map<String, Object> entry = aggregate.computeIfAbsent(name, key -> {
                    Map<String, Object> fresh = new LinkedHashMap<>();
                    fresh.put("name", key);
                    fresh.put("cusip", holding.get("cusip"));
                    fresh.put("buyers", new ArrayList<String>());
                    fresh.put("totalValue", 0.0);
                    fresh.put("weightSum", 0.0);
                    fresh.put("reportDate", Json.asText(current, "reportDate"));
                    return fresh;
                });

                @SuppressWarnings("unchecked")
                List<String> buyers = (List<String>) entry.get("buyers");
                buyers.add(institution);

                double value = holding.get("value") instanceof Double d ? d : 0.0;
                double weight = holding.get("weight") instanceof Double w ? w : 0.0;
                entry.put("totalValue", (double) entry.get("totalValue") + value);
                entry.put("weightSum", (double) entry.get("weightSum") + weight);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> entry : aggregate.values()) {
            @SuppressWarnings("unchecked")
            List<String> buyers = (List<String>) entry.get("buyers");
            if (buyers.size() < Math.max(1, minHolders)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>(entry);
            row.put("buyerCount", buyers.size());
            row.put("avgWeight", (double) entry.get("weightSum") / buyers.size());
            row.remove("weightSum");
            rows.add(row);
        }

        rows.sort(Comparator
                .comparingInt((Map<String, Object> row) -> (int) row.get("buyerCount"))
                .thenComparingDouble(row -> (double) row.get("totalValue"))
                .reversed());

        out.put("participants", participants);
        out.put("participantCount", participants.size());
        out.put("availableDates", availableDates.stream().sorted(Comparator.reverseOrder()).toList());
        out.put("reportDate", reportDate);
        out.put("minHolders", minHolders);
        out.put("rows", rows);
        out.put("available", !rows.isEmpty());
        out.put("note", "직전 분기와 비교할 수 없는 항목(주식 수 누락 등)은 세지 않습니다. "
                + "13F는 분기 공시이며 45일 지연입니다.");
        return out;
    }

    /**
     * 저장본 읽기.
     *
     * <p>q1은 q8의 앞부분이므로, 짧은 요청도 긴 저장본에서 잘라 씁니다
     * (수집기가 q8만 받아 둡니다).
     */
    private Optional<Snapshot> readHistory(String cik, int quarters) {
        int stored = Math.max(quarters, Datasets.MAX_TRACKED_QUARTERS);
        Optional<Snapshot> snapshot = store.read(
                Datasets.sec13f(cik, stored), Datasets.MAX_AGE_SLOW, "sec_13f");
        if (snapshot.isPresent()) {
            return snapshot;
        }
        return store.read(Datasets.sec13f(cik, quarters), Datasets.MAX_AGE_SLOW, "sec_13f");
    }

    public Map<String, String> institutionByCik(String cik) {
        return INSTITUTIONS.stream()
                .filter(entry -> cik.equals(entry.get("cik")))
                .findFirst()
                .orElse(Map.of("cik", cik, "name", cik, "desc", ""));
    }

    private static Double subtract(Double a, Double b) {
        if (a == null && b == null) {
            return null;
        }
        return (a == null ? 0.0 : a) - (b == null ? 0.0 : b);
    }

    private static Double negate(Double value) {
        return value == null ? null : -value;
    }

    private static List<Map<String, String>> institutions() {
        List<Map<String, String>> list = new ArrayList<>();
        list.add(inst("nps", "🇰🇷 국민연금 (National Pension Service)", "0001608046",
                "글로벌 자산배분 및 미국 대형 우량주 중심 장기 투자"));
        list.add(inst("norges", "🇳🇴 노르웨이 국부펀드 (Norges Bank / GPFG)", "0001374170",
                "세계 최대 규모의 글로벌 국부펀드, 인덱스형 거인"));
        list.add(inst("cppib", "🇨🇦 캐나다 연금투자위원회 (CPPIB)", "0001283718",
                "캐나다 연금을 운용하는 대형 연기금, 글로벌 자산배분 중심"));
        list.add(inst("apg", "🇳🇱 네덜란드 연금자산운용 (APG Asset Management)", "0001434819",
                "네덜란드 최대 연기금 자산운용사, 글로벌 분산투자"));
        list.add(inst("pif", "🇸🇦 사우디 국부펀드 (Public Investment Fund - PIF)", "0001767640",
                "대규모 글로벌 전략적 투자, 공격적 성장 베팅"));
        list.add(inst("blackrock", "🇺🇸 블랙록 (BlackRock)", "0002012383",
                "세계 최대 자산운용사, 광범위한 글로벌 자산군"));
        list.add(inst("vanguard", "🇺🇸 뱅가드 (Vanguard Group)", "0000102909",
                "글로벌 인덱스 펀드의 거두, 시장 전체를 아우르는 포트폴리오"));
        list.add(inst("berkshire", "🇺🇸 버크셔 해서웨이 (Berkshire Hathaway)", "0001067983",
                "가치투자 포트폴리오, 핵심 우량주 집중"));
        list.add(inst("duquesne", "🇺🇸 듀케인 패밀리 오피스 (Duquesne Family Office)", "0001536411",
                "스탠리 드러켄밀러, 테크 트렌드 포착형 매크로 운용"));
        list.add(inst("fisher", "🇺🇸 피셔 자산운용 (Fisher Asset Management)", "0000850529",
                "켄 피셔의 글로벌 성장주·빅테크 중심 탑다운 롱온리 전략"));
        list.add(inst("bridgewater", "🇺🇸 브리지워터 어소시에이츠 (Bridgewater)", "0001350694",
                "레이 달리오 설립, 올웨더 및 글로벌 매크로 헤지펀드"));
        list.add(inst("scion", "🇺🇸 사이언 자산운용 (Scion Asset Management)", "0001649339",
                "마이클 버리의 역발상 딥밸류 및 숏/롱 전략"));
        return List.copyOf(list);
    }

    private static Map<String, String> inst(String key, String name, String cik, String desc) {
        return Map.of("key", key, "name", name, "cik", cik, "desc", desc);
    }
}
