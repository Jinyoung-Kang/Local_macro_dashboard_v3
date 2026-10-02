package com.macrodash.feature.institution;

import com.macrodash.analytics.Holding;
import com.macrodash.analytics.HoldingsDiff;
import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import com.macrodash.support.HoldingsJson;
import com.macrodash.support.Json;
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

    private final StoreReader store;

    public Sec13FService(StoreReader store) {
        this.store = store;
    }

    /** 기관 1곳의 분기 이력 + 최신 분기 QoQ 분석. */
    public Map<String, Object> portfolio(String cik, int quarters, int topN) {
        Institutions.requireKnownCik(cik);
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
        out.put("institution", Institutions.byCik(cik));

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

        Map<String, Object> latestBlock = new LinkedHashMap<>();   // Map.of는 null(모르는 평가액)을 거부합니다
        latestBlock.put("reportDate", String.valueOf(Json.asText(latest, "reportDate")));
        latestBlock.put("filingDate", String.valueOf(Json.asText(latest, "filingDate")));
        latestBlock.put("totalValue", Json.asDouble(latest, "totalValue"));
        out.put("latest", latestBlock);
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
     * 최신 분기와 직전 분기를 대조해 종목별 액션을 분류합니다(계산은 {@link HoldingsDiff}).
     *
     * @return 평가액 내림차순, topN까지. 전량 매도 행은 평가액·주식 수·비중이 0입니다
     */
    List<Map<String, Object>> compareQuarters(JsonNode current, JsonNode previous, int topN) {
        List<HoldingsDiff.Change> changes = HoldingsDiff.compare(
                HoldingsJson.holdings(current),
                previous == null ? null : HoldingsJson.holdings(previous));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (HoldingsDiff.Change change : changes) {
            Holding holding = change.holding();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", holding.name());
            // CUSIP은 숫자로만 이루어질 수 있어 반드시 문자열로 다룹니다.
            row.put("cusip", holding.cusip());
            row.put("class", holding.cls());
            row.put("value", holding.value());
            row.put("shares", holding.shares());
            row.put("weight", holding.weight());
            row.put("weightDiff", change.weightDiff());
            row.put("sharesDiff", change.sharesDiff());
            row.put("action", change.action());
            rows.add(row);
        }

        rows.sort(Comparator.comparingDouble(
                (Map<String, Object> row) -> row.get("value") instanceof Double d ? d : 0.0
        ).reversed());

        return topN > 0 && rows.size() > topN ? rows.subList(0, topN) : rows;
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
        ciks.forEach(Institutions::requireKnownCik);   // 저장본을 찾기 전에 전부 확인합니다
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
            String institution = Institutions.byCik(cik).getOrDefault("name", cik);
            participants.add(institution);

            for (Map<String, Object> holding : compareQuarters(current, previous, 100)) {
                String name = String.valueOf(holding.get("name"));
                Map<String, Object> entry = aggregate.computeIfAbsent(name, key -> {
                    Map<String, Object> fresh = new LinkedHashMap<>();
                    fresh.put("name", key);
                    fresh.put("cusip", holding.get("cusip"));
                    fresh.put("holders", new ArrayList<String>());
                    fresh.put("actions", new ArrayList<String>());
                    fresh.put("totalValue", null);
                    fresh.put("weightSum", null);
                    fresh.put("weightCount", 0);
                    fresh.put("maxWeight", null);
                    return fresh;
                });

                @SuppressWarnings("unchecked")
                List<String> holders = (List<String>) entry.get("holders");
                @SuppressWarnings("unchecked")
                List<String> actions = (List<String>) entry.get("actions");

                holders.add(institution);
                actions.add(String.valueOf(holding.get("action")));
                // 모르는 평가액·비중은 더하지 않고, 평균의 분모에도 넣지 않습니다. 0으로 더하면
                // 합계와 평균이 실제보다 작아지고 정렬 순서까지 바뀝니다.
                addKnown(entry, holding);
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
            row.put("avgWeight", averageKnownWeight(entry));
            row.remove("weightCount");
            row.put("buyCount", actions.stream()
                    .filter(a -> a.contains("신규 매수") || a.contains("비중 확대")).count());
            row.put("sellCount", actions.stream()
                    .filter(a -> a.contains("전량 매도") || a.contains("비중 축소")).count());
            row.remove("weightSum");
            rows.add(row);
        }

        rows.sort(Comparator
                .comparingInt((Map<String, Object> row) -> (int) row.get("holderCount"))
                .thenComparingDouble(Sec13FService::knownTotalValue)
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
        ciks.forEach(Institutions::requireKnownCik);   // 저장본을 찾기 전에 전부 확인합니다
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
            String institution = Institutions.byCik(cik).getOrDefault("name", cik);
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
                    fresh.put("totalValue", null);
                    fresh.put("weightSum", null);
                    fresh.put("weightCount", 0);
                    fresh.put("reportDate", Json.asText(current, "reportDate"));
                    return fresh;
                });

                @SuppressWarnings("unchecked")
                List<String> buyers = (List<String>) entry.get("buyers");
                buyers.add(institution);
                addKnown(entry, holding);
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
            row.put("avgWeight", averageKnownWeight(entry));
            row.remove("weightSum");
            row.remove("weightCount");
            rows.add(row);
        }

        rows.sort(Comparator
                .comparingInt((Map<String, Object> row) -> (int) row.get("buyerCount"))
                .thenComparingDouble(Sec13FService::knownTotalValue)
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

    /** 집계 항목에 알려진 평가액·비중만 더합니다(모르는 값은 합계·분모·최대에서 제외). */
    private static void addKnown(Map<String, Object> entry, Map<String, Object> holding) {
        if (holding.get("value") instanceof Double value) {
            Double total = (Double) entry.get("totalValue");
            entry.put("totalValue", total == null ? value : total + value);
        }
        if (holding.get("weight") instanceof Double weight) {
            Double sum = (Double) entry.get("weightSum");
            entry.put("weightSum", sum == null ? weight : sum + weight);
            entry.put("weightCount", (int) entry.get("weightCount") + 1);
            Double max = (Double) entry.get("maxWeight");
            if (entry.containsKey("maxWeight")) {
                entry.put("maxWeight", max == null ? weight : Math.max(max, weight));
            }
        }
    }

    /** 비중을 아는 기관들의 평균. 하나도 모르면 null. */
    private static Double averageKnownWeight(Map<String, Object> entry) {
        int count = (int) entry.get("weightCount");
        return count == 0 ? null : (Double) entry.get("weightSum") / count;
    }

    /** 정렬용 — 모르는 합계는 가장 뒤로. */
    private static double knownTotalValue(Map<String, Object> row) {
        return row.get("totalValue") instanceof Double d ? d : Double.NEGATIVE_INFINITY;
    }



}
