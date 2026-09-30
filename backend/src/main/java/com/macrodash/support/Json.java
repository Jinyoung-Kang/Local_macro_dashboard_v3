package com.macrodash.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 수집기가 적재한 JSON을 안전하게 읽는 헬퍼.
 *
 * <p><b>모든 접근자는 값이 없거나 형이 다르면 {@code null} 또는 빈 목록</b>을
 * 돌려줍니다. 예외를 던지지도, 0으로 대체하지도 않습니다 — 수집 실패가 화면에서
 * "보합"으로 읽히는 것을 막기 위해서입니다.
 *
 * <p>저장본은 외부 소스에서 온 것이라 필드가 없거나 타입이 바뀔 수 있습니다.
 * 저장본을 읽는 코드는 이 클래스만 쓰고 {@code JsonNode}를 직접 다루지 마세요.
 */
public final class Json {

    private Json() {
    }

    public static Double asDouble(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        return value.asDouble();
    }

    public static String asText(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    public static boolean asBoolean(JsonNode node, String field) {
        if (node == null) {
            return false;
        }
        JsonNode value = node.get(field);
        return value != null && value.asBoolean(false);
    }

    public static JsonNode child(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value;
    }

    public static List<JsonNode> array(JsonNode node, String field) {
        JsonNode value = child(node, field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>();
        value.forEach(out::add);
        return out;
    }

    /** {"points":[{"date","value"}]} 형태에서 값만 추립니다. */
    public static List<Double> pointValues(JsonNode payload) {
        List<Double> values = new ArrayList<>();
        for (JsonNode point : array(payload, "points")) {
            Double value = asDouble(point, "value");
            if (value == null) {
                value = asDouble(point, "close");
            }
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    /** {"points":[{"date",...}]} 형태에서 날짜만 추립니다(값이 있는 점만). */
    public static List<LocalDate> pointDates(JsonNode payload) {
        List<LocalDate> dates = new ArrayList<>();
        for (JsonNode point : array(payload, "points")) {
            Double value = asDouble(point, "value");
            if (value == null) {
                value = asDouble(point, "close");
            }
            if (value == null) {
                continue;
            }
            LocalDate date = parseDate(asText(point, "date"));
            if (date != null) {
                dates.add(date);
            }
        }
        return dates;
    }

    public static LocalDate parseDate(String text) {
        if (text == null || text.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }
}
