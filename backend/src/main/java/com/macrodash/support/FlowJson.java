package com.macrodash.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.macrodash.analytics.FlowRecord;
import com.macrodash.analytics.InvestorFlows;
import com.macrodash.analytics.Json;
import com.macrodash.analytics.SupplyConsensus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 저장본(JSON)을 수급 계산이 받는 일반 타입으로 바꿉니다.
 *
 * <p>계산 코드({@code analytics})는 JSON 라이브러리를 모릅니다. JSON을 읽는 일은 여기서
 * 끝내고, 계산에는 값만 넘깁니다 — JSON 라이브러리가 바뀌어도(예: Jackson 2 → 3) 계산
 * 코드는 그대로입니다.
 *
 * <p>읽는 규칙은 {@link Json}과 같습니다. 값이 없거나 형이 다르면 null이고, 0으로 채우지
 * 않습니다. 금액(buy·sell·net)은 <b>정수일 때만</b> 읽습니다(수집기는 정수로 저장합니다).
 */
public final class FlowJson {

    private FlowJson() {
    }

    /**
     * 투자자별 매매 기록 하나.
     *
     * @param node {@code {date, updatedAt, investors:{…}, breakdown:{…}|null, foreignerHoldingRate}}.
     *             null이나 객체가 아닌 값이면 모든 칸이 "모름"인 기록
     */
    public static FlowRecord record(JsonNode node) {
        return new FlowRecord(
                Json.asText(node, "date"),
                Json.asText(node, "updatedAt"),
                amounts(Json.child(node, FlowRecord.INVESTORS)),
                amounts(Json.child(node, FlowRecord.BREAKDOWN)),
                Json.asDouble(node, "foreignerHoldingRate"));
    }

    /** 순서를 유지해 기록 목록을 바꿉니다(최신이 앞인 순서 그대로). */
    public static List<FlowRecord> records(List<JsonNode> nodes) {
        return nodes.stream().map(FlowJson::record).toList();
    }

    /** Daum 선물 수급 행 목록 ({@code investor, netToday, net5d, net20d}). */
    public static List<InvestorFlows.FuturesRow> futuresRows(List<JsonNode> nodes) {
        return nodes.stream().map(node -> new InvestorFlows.FuturesRow(
                Json.asText(node, "investor"),
                Json.asDouble(node, "netToday"),
                Json.asDouble(node, "net5d"),
                Json.asDouble(node, "net20d"))).toList();
    }

    /** 수급 상위 목록 ({@code code, name, price, changePct, netAmountEok, rank}). */
    public static List<SupplyConsensus.Row> rankRows(List<JsonNode> nodes) {
        return nodes.stream().map(node -> new SupplyConsensus.Row(
                Json.asText(node, "code"),
                Json.asText(node, "name"),
                Json.asDouble(node, "price"),
                Json.asDouble(node, "changePct"),
                Json.asDouble(node, "netAmountEok"),
                Json.asDouble(node, "rank"))).toList();
    }

    /** {@code {key: {buy, sell, net} | null}} — 값이 객체인 키만 담습니다(null인 분류는 "모름"). */
    private static Map<String, FlowRecord.Amounts> amounts(JsonNode group) {
        Map<String, FlowRecord.Amounts> out = new LinkedHashMap<>();
        if (group == null || !group.isObject()) {
            return out;
        }
        for (Map.Entry<String, JsonNode> entry : group.properties()) {
            JsonNode value = entry.getValue();
            if (value != null && value.isObject()) {
                out.put(entry.getKey(), new FlowRecord.Amounts(
                        integral(value, "buy"), integral(value, "sell"), integral(value, "net")));
            }
        }
        return out;
    }

    private static Long integral(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isIntegralNumber() ? value.asLong() : null;
    }
}
