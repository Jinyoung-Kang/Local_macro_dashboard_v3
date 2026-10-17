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
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

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
        // 직전 분기는 바로 앞 분기 말일의 공시입니다. 그 분기가 저장돼 있지 않으면 비교하지 않습니다(QA-085).
        // 고른 분기 수(selected)가 아니라 저장된 전체에서 찾습니다 — 분기 수는 목록·히트맵 범위일 뿐이라,
        // '최근 1개 분기'를 골랐다고 저장된 직전 분기를 버리면 모든 액션이 '비교 데이터 없음'이 됐습니다(QA-086).
        JsonNode previous = HoldingsJson.previousQuarter(allQuarters, latest);

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

    /**
     * 교집합에 넣을 한 기관의 행 — <b>지금 들고 있는</b> 항목은 평가액 상위 {@code limit}개, 전량 매도 행은 전부.
     *
     * <p>전량 매도 행은 평가액이 0이라 평가액 순으로 함께 자르면 늘 맨 뒤로 밀립니다. 그러면 보유 종목이
     * {@code limit}개보다 적은 기관의 전량 매도만 '동시 매도'로 세어져, 같은 매도가 기관 규모에 따라 세어지기도
     * 하고 빠지기도 했습니다(QA-082). 자르기는 보유 항목에만 하고, 전량 매도는 따로 붙입니다.
     */
    private List<Map<String, Object>> consensusRows(JsonNode current, JsonNode previous, int limit) {
        List<Map<String, Object>> held = new ArrayList<>();
        List<Map<String, Object>> closed = new ArrayList<>();
        for (Map<String, Object> row : compareQuarters(current, previous, 0)) {
            if (HoldingsDiff.CLOSED.equals(row.get("action"))) {
                closed.add(row);
            } else {
                held.add(row);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(held.size() > limit ? held.subList(0, limit) : held);
        out.addAll(closed);
        return out;
    }

    /**
     * 상위 종목의 분기별 비중 추이 (차트용).
     *
     * <p>줄 하나는 <b>회사(이름) 하나</b>이고, 값은 그 분기에 같은 이름으로 든 항목들의 비중 합입니다 — 교집합이 한 기관의
     * A·C주를 합치는 것과 같은 규칙입니다(QA-084). 예전에는 이름을 항목마다 목록에 넣고 분기마다 첫 항목만 읽어,
     * ALPHABET A·C주가 상위에 함께 있으면 줄 하나가 덮여 사라지고 값은 A주 비중뿐이었습니다. 상위는 최신 분기의
     * 합친 비중 순으로 고릅니다.
     */
    private Map<String, Object> weightHistory(List<JsonNode> quarters, int topN) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (quarters.isEmpty()) {
            return out;
        }

        List<String> dates = new ArrayList<>();
        // 최신이 앞이므로 차트용으로 과거 → 최신 순서로 뒤집습니다.
        List<JsonNode> ordered = new ArrayList<>(quarters);
        java.util.Collections.reverse(ordered);
        ordered.forEach(q -> dates.add(Json.asText(q, "reportDate")));

        List<Map<String, Double>> weightsByQuarter = ordered.stream().map(Sec13FService::weightByName).toList();
        Map<String, Double> latest = weightsByQuarter.get(weightsByQuarter.size() - 1);
        // 비중을 모르는 회사는 맨 뒤로. 같은 비중이면 저장본 순서(평가액 순)를 지킵니다(정렬은 안정적).
        List<String> names = latest.keySet().stream()
                .sorted(Comparator.comparingDouble((String name) ->
                        latest.get(name) == null ? Double.NEGATIVE_INFINITY : latest.get(name)).reversed())
                .limit(Math.max(1, Math.min(topN, 15)))
                .toList();

        Map<String, List<Double>> series = new LinkedHashMap<>();
        for (String name : names) {
            List<Double> values = new ArrayList<>();
            for (Map<String, Double> weights : weightsByQuarter) {
                // 들고 있지 않은 분기는 0%가 맞습니다(데이터 없음이 아니라 미보유). 들고 있는데 비중을 모르면 null입니다.
                values.add(weights.containsKey(name) ? weights.get(name) : Double.valueOf(0.0));
            }
            series.put(name, values);
        }

        out.put("dates", dates);
        out.put("series", series);
        return out;
    }

    /**
     * 한 분기의 이름 → 비중 합(저장본 순서 유지). 같은 이름의 항목(A·C주)은 아는 비중끼리 더하고,
     * 그 이름의 비중을 하나도 모르면 null입니다 — 0으로 두면 '들고 있지 않음'과 구별되지 않습니다.
     */
    private static Map<String, Double> weightByName(JsonNode quarter) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (JsonNode holding : Json.array(quarter, "holdings")) {
            String name = Json.asText(holding, "name");
            if (name == null) {
                continue;
            }
            Double weight = Json.asDouble(holding, "weight");
            Double sum = out.get(name);
            // 삼항식으로 쓰면 Double과 double이 섞여 null이 언박싱됩니다(NPE). 나눠서 씁니다.
            if (sum == null) {
                out.put(name, weight);
            } else if (weight != null) {
                out.put(name, sum + weight);
            }
        }
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
        Map<String, Tally> aggregate = new LinkedHashMap<>();
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
            // 바로 앞 분기가 빠진 기관은 6개월 변화를 이번 분기 매매로 세지 않습니다 — 액션은 '비교 데이터 없음'(QA-085).
            JsonNode previous = HoldingsJson.previousQuarter(quarters, current);
            String institution = Institutions.byCik(cik).getOrDefault("name", cik);
            participants.add(institution);

            for (Map<String, Object> holding : consensusRows(current, previous, 100)) {
                aggregate.computeIfAbsent(securityKey(holding), key -> new Tally(key, holding.get("cusip"), null))
                        .add(institution, holding);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Tally tally : mergeShareClasses(aggregate.values())) {
            // 전량 매도만 모인 집계는 보유 기관이 0곳입니다. minHolders가 0 이하로 와도 '보유'로 올리지 않습니다.
            if (tally.holders.size() < Math.max(1, minHolders)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", tally.key);
            row.put("name", tally.displayName());
            row.put("cusip", tally.cusip);
            row.put("holders", new ArrayList<>(tally.holders));
            row.put("actions", tally.actions);
            row.put("totalValue", tally.totalValue);
            row.put("maxWeight", tally.maxWeight());
            row.put("holderCount", tally.holders.size());
            row.put("avgWeight", tally.avgWeight());
            row.put("buyCount", (long) tally.buyers.size());
            row.put("sellCount", (long) tally.sellers.size());
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
        Map<String, Tally> aggregate = new LinkedHashMap<>();
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
            JsonNode previous = HoldingsJson.previousQuarter(quarters, current);
            if (previous == null) {
                // 바로 앞 분기가 저장돼 있지 않으면(분기가 빠진 기관) 신규 매수를 판정할 수 없습니다(QA-085).
                continue;
            }
            String institution = Institutions.byCik(cik).getOrDefault("name", cik);
            participants.add(institution);

            for (Map<String, Object> holding : compareQuarters(current, previous, 200)) {
                String action = String.valueOf(holding.get("action"));
                if (!action.contains("신규 매수")) {
                    continue;
                }
                aggregate.computeIfAbsent(securityKey(holding),
                                key -> new Tally(key, holding.get("cusip"), Json.asText(current, "reportDate")))
                        .add(institution, holding);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Tally tally : mergeShareClasses(aggregate.values())) {
            if (tally.holders.size() < Math.max(1, minHolders)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", tally.key);
            row.put("name", tally.displayName());
            row.put("cusip", tally.cusip);
            row.put("buyers", new ArrayList<>(tally.holders));
            row.put("totalValue", tally.totalValue);
            row.put("reportDate", tally.reportDate);
            row.put("buyerCount", tally.holders.size());
            row.put("avgWeight", tally.avgWeight());
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

    /**
     * 교집합·공통 신규 매수의 집계 단위 — 공시 이름이 아니라 <b>증권(CUSIP)</b>입니다(QA-083).
     *
     * <p>13F의 종목 이름(nameOfIssuer)은 제출 기관이 자유롭게 적습니다. 같은 NVIDIA를 한 곳은 'NVIDIA CORPORATION',
     * 다른 곳은 'NVIDIA CORP COM'으로 적어, 이름으로 묶으면 보유 기관 수가 갈라졌습니다. 반대로 'ISHARES TR' 한 이름
     * 아래 서로 다른 ETF가 한 행으로 합쳐지기도 했습니다. CUSIP이 없으면 이름+종류입니다(분기 대비 키와 같은 규칙).
     */
    static String securityKey(Map<String, Object> holding) {
        Object cusip = holding.get("cusip");
        String text = cusip == null ? "" : String.valueOf(cusip).trim();
        if (!text.isEmpty()) {
            return "cusip:" + text;
        }
        Object cls = holding.get("class");
        return "name:" + holding.get("name") + "|" + (cls == null ? "" : String.valueOf(cls).trim());
    }

    /** 보통주 계열 클래스 표기(COM, CL A, CAP STK CL C, ORD SHS …). */
    private static final Pattern COMMON_CLASS =
            Pattern.compile("\\b(COM|COMMON|CL|CLASS|ORD|ORDINARY|SHS|SHARES|STK|STOCK)\\b");

    /** 보통주가 아닌 표기 — 우선주·예탁증서·채권·워런트·유닛·펀드 등. 하나라도 있으면 합치지 않습니다. */
    private static final Pattern NOT_COMMON_CLASS = Pattern.compile("(^|\\s)\\*?W\\b|\\b(PFD|PREF|PREFERRED|PRF|PERP"
            + "|DEP|DEPOSITARY|NOTE|NOTES|DEB|DEBT|BOND|BD|BDS|CONV|CV|UNIT|UNITS|WT|WTS|WARRANT|WARRANTS|RIGHT|RIGHTS|RT"
            + "|ETF|ETN|FUND|FD|FDS|TR|TRUST|INDEX|IDX|BEN|ADR|ADS|SPDR|ISHARES|PORTFOLIO|PORTFOLIOS|SERIES|TRADED)\\b");

    /** 펀드·ETF 발행사 이름(ISHARES TR, VANGUARD INDEX FDS, SPDR SERIES TRUST …). */
    private static final Pattern FUND_NAME = Pattern.compile(
            "\\b(ETF|ETN|FUND|FD|FDS|TR|TRUST|INDEX|IDX|ISHARES|SPDR|PORTFOLIO|PORTFOLIOS|SERIES|TRADED)\\b");

    /**
     * 한 회사의 여러 보통주 클래스(ALPHABET A·C주, BERKSHIRE A·B주)를 한 행으로 합칩니다 — 리뷰 B-2의 결정을
     * 증권 키 위에서 지킵니다. 한 기관이 두 클래스를 들어도 보유 기관 1곳이고, 그 기관의 비중은 두 항목의 합입니다.
     *
     * <p>합치는 조건은 둘 다입니다. ① CUSIP 앞 6자리(발행사)가 같고 ② 그 증권에 붙은 클래스 표기가 모두 보통주 계열이며
     * 펀드·ETF 이름이 아닐 것. 앞 6자리만 보면 iShares Trust(464287)처럼 한 발행사 아래 서로 다른 ETF 수백 개가 한 행이
     * 되고, 같은 회사의 보통주와 우선주·전환사채도 합쳐집니다. 조건을 못 맞추면 합치지 않습니다 — 한 회사가 두 행으로
     * 보이는 쪽이 다른 증권을 섞는 쪽보다 덜 틀립니다.
     */
    private static List<Tally> mergeShareClasses(Collection<Tally> tallies) {
        Map<String, Tally> byKey = new LinkedHashMap<>();
        for (Tally tally : tallies) {
            String issuer = tally.commonStockIssuer();
            if (issuer == null) {
                byKey.put(tally.key, tally);
                continue;
            }
            String key = "issuer:" + issuer;
            Tally first = byKey.get(key);
            if (first == null) {
                tally.key = key;
                byKey.put(key, tally);
            } else {
                first.absorb(tally);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * 증권 하나(또는 한 회사의 보통주 클래스들)의 집계. <b>기관 단위</b>로 셉니다 — 한 기관이 같은 회사를 두 클래스로
     * 들어도 보유 기관 1곳이고, 그 기관의 비중은 두 항목의 합입니다. 항목 단위로 세면 그 기관이 두 번 들어가
     * holderCount·minHolders·정렬이 틀어졌습니다.
     *
     * <p>모르는 평가액·비중은 더하지 않고 평균의 분모에도 넣지 않습니다. 0으로 더하면 합계와
     * 평균이 실제보다 작아지고 정렬 순서까지 바뀝니다.
     *
     * <p><b>전량 매도 행은 보유가 아닙니다</b>(QA-082). 그 기관은 '동시 매도'(sellers)와 액션 목록에만 들어가고,
     * 보유 기관·평가액·비중에는 들어가지 않습니다. 예전에는 평가액·비중 0인 전량 매도 행을 그대로 더해, 지금 들고
     * 있지 않은 기관이 '보유 기관'으로 세어지고 평균 비중이 그만큼 낮아졌습니다. 한 기관이 A주는 들고 C주만 전량
     * 매도했다면 A주 행으로 보유 기관에 남습니다 — 보유 기관은 '전량 매도가 아닌 행이 하나라도 있는 기관'입니다.
     */
    private static final class Tally {
        /**
         * 행의 고유 키 — {@code cusip:…}, 보통주 클래스를 합친 행은 {@code issuer:…}(CUSIP 앞 6자리), CUSIP이 없으면
         * {@code name:…}. 표시 이름은 겹칠 수 있어(서로 다른 ETF의 'ISHARES TR') 화면은 이 키로 행을 구분합니다.
         */
        String key;
        /** 대표 CUSIP(처음 들어온 증권의 것). 모르면 null. */
        final Object cusip;
        final String reportDate;
        /** 공시 이름 → 그 이름을 쓴 기관. 표시 이름은 가장 많은 기관이 쓴 이름입니다. */
        final Map<String, Set<String>> nameUsers = new LinkedHashMap<>();
        /** 기관들이 이 증권에 붙인 클래스 표기(대문자, 공백 정리). 합칠지 판단할 때만 씁니다. */
        final Set<String> classes = new LinkedHashSet<>();
        final Set<String> holders = new LinkedHashSet<>();
        final List<String> actions = new ArrayList<>();
        final Set<String> buyers = new LinkedHashSet<>();
        final Set<String> sellers = new LinkedHashSet<>();
        /** 기관 → 그 회사 비중의 합(아는 것만). */
        final Map<String, Double> weightByHolder = new LinkedHashMap<>();
        Double totalValue;

        Tally(String key, Object cusip, String reportDate) {
            this.key = key;
            this.cusip = cusip;
            this.reportDate = reportDate;
        }

        void add(String institution, Map<String, Object> holding) {
            if (holding.get("name") != null) {
                nameUsers.computeIfAbsent(String.valueOf(holding.get("name")), name -> new LinkedHashSet<>())
                        .add(institution);
            }
            Object cls = holding.get("class");
            classes.add(cls == null ? "" : String.join(" ",
                    String.valueOf(cls).toUpperCase(Locale.ROOT).trim().split("\\s+")));

            String action = String.valueOf(holding.get("action"));
            actions.add(action);
            if (action.contains("신규 매수") || action.contains("비중 확대")) {
                buyers.add(institution);
            }
            if (action.contains("전량 매도") || action.contains("비중 축소")) {
                sellers.add(institution);
            }
            if (HoldingsDiff.CLOSED.equals(action)) {
                return;   // 지금 들고 있지 않습니다 — 보유 기관·평가액·비중에 넣지 않습니다
            }
            holders.add(institution);
            if (holding.get("value") instanceof Double value) {
                totalValue = totalValue == null ? value : totalValue + value;
            }
            if (holding.get("weight") instanceof Double weight) {
                weightByHolder.merge(institution, weight, Double::sum);
            }
        }

        /** 같은 회사의 다른 보통주 클래스를 이 행에 더합니다. 기관이 겹치면 그 기관의 비중은 합입니다. */
        void absorb(Tally other) {
            other.nameUsers.forEach((name, users) ->
                    nameUsers.computeIfAbsent(name, key -> new LinkedHashSet<>()).addAll(users));
            classes.addAll(other.classes);
            holders.addAll(other.holders);
            actions.addAll(other.actions);
            buyers.addAll(other.buyers);
            sellers.addAll(other.sellers);
            other.weightByHolder.forEach((institution, weight) -> weightByHolder.merge(institution, weight, Double::sum));
            if (other.totalValue != null) {
                totalValue = totalValue == null ? other.totalValue : totalValue + other.totalValue;
            }
        }

        /**
         * 보통주 계열이고 펀드가 아니면 발행사(CUSIP 앞 6자리), 아니면 null(합치지 않음).
         * 기관마다 표기가 달라도 <b>모든</b> 표기가 조건을 맞춰야 합니다.
         */
        String commonStockIssuer() {
            String text = cusip == null ? "" : String.valueOf(cusip).trim();
            if (!key.startsWith("cusip:") || text.length() < 6 || classes.isEmpty()) {
                return null;
            }
            for (String cls : classes) {
                if (!COMMON_CLASS.matcher(cls).find() || NOT_COMMON_CLASS.matcher(cls).find()) {
                    return null;
                }
            }
            for (String name : nameUsers.keySet()) {
                if (FUND_NAME.matcher(name.toUpperCase(Locale.ROOT)).find()) {
                    return null;
                }
            }
            return text.substring(0, 6).toUpperCase(Locale.ROOT);
        }

        /** 가장 많은 기관이 쓴 이름. 같으면 짧은 이름, 그래도 같으면 사전순 — 같은 데이터에서 늘 같은 이름이 나옵니다. */
        String displayName() {
            return nameUsers.entrySet().stream()
                    .min(Comparator.<Map.Entry<String, Set<String>>>comparingInt(entry -> -entry.getValue().size())
                            .thenComparingInt(entry -> entry.getKey().length())
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .orElseGet(() -> cusip == null ? "(이름 없음)" : String.valueOf(cusip));
        }

        /** 비중을 아는 기관들의 평균. 하나도 모르면 null. */
        Double avgWeight() {
            return weightByHolder.isEmpty() ? null
                    : weightByHolder.values().stream().mapToDouble(Double::doubleValue).sum() / weightByHolder.size();
        }

        Double maxWeight() {
            return weightByHolder.isEmpty() ? null
                    : weightByHolder.values().stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
        }
    }

    /** 정렬용 — 모르는 합계는 가장 뒤로. */
    private static double knownTotalValue(Map<String, Object> row) {
        return row.get("totalValue") instanceof Double d ? d : Double.NEGATIVE_INFINITY;
    }



}
