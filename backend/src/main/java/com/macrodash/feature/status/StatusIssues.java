package com.macrodash.feature.status;

import com.macrodash.Kst;
import com.macrodash.support.SecretRedactor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 수집 오류·경고 모음 — 상태 화면의 "복사해서 붙여 넣기 좋은" 로그.
 *
 * <p>상태 화면은 태스크별 "최근 결과"만 보여 줍니다. 한 번 실패했다가 다음 주기에
 * 성공하면 흔적이 사라지고, 사유가 긴 줄은 표에서 잘립니다. 문제를 누군가에게
 * 보여 주려면 스크린샷을 여러 장 찍어야 했습니다. 그래서 문제만 모아 한 덩어리의
 * 텍스트로 만듭니다.
 *
 * <p>순수 계산입니다(저장소를 모릅니다). 입력은 실행 기록 행, 출력은 항목과 텍스트.
 *
 * <p>주의사항
 * <ul>
 *   <li>같은 태스크·같은 사유가 반복되면 한 줄로 묶고 횟수를 적습니다. 5분 주기
 *       태스크가 하루 종일 같은 이유로 실패하면 288줄이 됩니다.</li>
 *   <li>모든 사유는 {@link SecretRedactor}를 거칩니다. 이 텍스트는 복사돼 밖으로 나갑니다.</li>
 *   <li>없앤 태스크의 옛 기록은 뺍니다({@code registeredTasks}).</li>
 * </ul>
 */
public final class StatusIssues {

    /** 한 사유의 최대 길이. 수집기가 1000자로 자르지만 텍스트 한 덩어리가 너무 길어지지 않게. */
    static final int MAX_DETAIL = 600;

    private StatusIssues() {
    }

    /**
     * @param latest          태스크별 최근 결과 (task, status, started_at, detail)
     * @param history         최근 실행 기록 중 ok가 아닌 행 (최신이 앞)
     * @param missingDatasets 있어야 하는데 없는 데이터셋 이름 (없으면 빈 목록)
     * @param lastRunStatus   마지막 수집 실행 상태 (ok·partial·fail·interrupted 등, 모르면 null)
     * @param registeredTasks 지금 등록된 태스크 이름. null이면 거르지 않음(수집기에 연결 못 함)
     * @param now             생성 시각
     * @return {@code counts}, {@code current}(지금 실패 중), {@code recent}(최근 실패 이력, 묶음),
     *         {@code missing}, {@code text}(복사용)
     */
    public static Map<String, Object> build(List<Map<String, Object>> latest,
                                            List<Map<String, Object>> history,
                                            List<String> missingDatasets,
                                            String lastRunStatus,
                                            Collection<String> registeredTasks,
                                            Instant now) {
        List<Map<String, Object>> current = new ArrayList<>();
        for (Map<String, Object> row : latest) {
            if (!isIssue(row) || !registered(row, registeredTasks)) {
                continue;
            }
            current.add(item(row, 1));
        }

        // (태스크, 상태, 사유)로 묶습니다. history가 최신 순이라 처음 본 행의 시각이 "마지막 발생".
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : history) {
            if (!isIssue(row) || !registered(row, registeredTasks)) {
                continue;
            }
            String key = row.get("task") + "|" + row.get("status") + "|" + detail(row);
            Map<String, Object> existing = grouped.get(key);
            if (existing == null) {
                grouped.put(key, item(row, 1));
            } else {
                existing.put("count", (int) existing.get("count") + 1);
                existing.put("firstAt", time(row.get("started_at")));
            }
        }
        List<Map<String, Object>> recent = new ArrayList<>(grouped.values());

        long errors = current.stream().filter(item -> "error".equals(item.get("level"))).count();
        long warnings = current.size() - errors;
        List<String> missing = missingDatasets == null ? List.of() : missingDatasets;

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("errors", errors);
        counts.put("warnings", warnings);
        counts.put("recentGroups", recent.size());
        counts.put("missingDatasets", missing.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", Kst.stamp(now));
        out.put("counts", counts);
        out.put("lastRunStatus", lastRunStatus);
        out.put("current", current);
        out.put("recent", recent);
        out.put("missing", missing);
        out.put("text", text(now, counts, lastRunStatus, current, recent, missing));
        return out;
    }

    private static boolean isIssue(Map<String, Object> row) {
        Object status = row.get("status");
        return status != null && !"ok".equals(status) && !"running".equals(status);
    }

    private static boolean registered(Map<String, Object> row, Collection<String> names) {
        return names == null || names.contains(String.valueOf(row.get("task")));
    }

    private static Map<String, Object> item(Map<String, Object> row, int count) {
        Map<String, Object> item = new LinkedHashMap<>();
        // error = 수집 중 예외, empty = 수집은 됐지만 쓸 데이터가 없어 기존 저장본 유지.
        item.put("level", "error".equals(row.get("status")) ? "error" : "warning");
        item.put("task", String.valueOf(row.get("task")));
        item.put("status", String.valueOf(row.get("status")));
        item.put("at", time(row.get("started_at")));
        item.put("firstAt", time(row.get("started_at")));
        item.put("count", count);
        item.put("detail", detail(row));
        return item;
    }

    private static String detail(Map<String, Object> row) {
        String text = SecretRedactor.redact(String.valueOf(row.getOrDefault("detail", "")));
        if (text == null || "null".equals(text)) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > MAX_DETAIL ? oneLine.substring(0, MAX_DETAIL) + "…" : oneLine;
    }

    /** DB의 시각(Timestamp·OffsetDateTime·문자열 등)을 화면 형식으로. 모르는 형식은 그대로. */
    static String time(Object value) {
        if (value == null) {
            return "—";
        }
        if (value instanceof java.sql.Timestamp ts) {
            return Kst.DISPLAY.format(ts.toInstant());
        }
        if (value instanceof java.time.temporal.TemporalAccessor temporal) {
            try {
                return Kst.DISPLAY.format(temporal);
            } catch (java.time.DateTimeException ignored) {
                return String.valueOf(value);
            }
        }
        try {
            return Kst.DISPLAY.format(java.time.OffsetDateTime.parse(String.valueOf(value)));
        } catch (java.time.format.DateTimeParseException ignored) {
            return String.valueOf(value);
        }
    }

    private static String text(Instant now, Map<String, Object> counts, String lastRunStatus,
                               List<Map<String, Object>> current, List<Map<String, Object>> recent,
                               List<String> missing) {
        StringBuilder out = new StringBuilder();
        out.append("[Local Macro Dashboard] 수집 오류·경고 로그\n");
        out.append("생성: ").append(Kst.stamp(now)).append('\n');
        out.append("요약: 현재 오류 ").append(counts.get("errors"))
                .append(" · 현재 경고 ").append(counts.get("warnings"))
                .append(" · 최근 실패 유형 ").append(counts.get("recentGroups"))
                .append(" · 누락 데이터셋 ").append(counts.get("missingDatasets"));
        if (lastRunStatus != null) {
            out.append(" · 마지막 실행 ").append(lastRunStatus);
        }
        out.append("\n\n■ 지금 실패 중인 태스크 (최근 결과 기준)\n");
        if (current.isEmpty()) {
            out.append("  없음\n");
        }
        for (Map<String, Object> item : current) {
            out.append("  [").append(label(item)).append("] ").append(item.get("task"))
                    .append(" · ").append(item.get("at")).append('\n')
                    .append("    ").append(item.get("detail")).append('\n');
        }
        out.append("\n■ 최근 실패 이력 (같은 사유는 한 줄로 묶음)\n");
        if (recent.isEmpty()) {
            out.append("  없음\n");
        }
        for (Map<String, Object> item : recent) {
            out.append("  [").append(label(item)).append("] ").append(item.get("task"))
                    .append(" × ").append(item.get("count"))
                    .append(" · ").append(item.get("firstAt"));
            if (!item.get("firstAt").equals(item.get("at"))) {
                out.append(" ~ ").append(item.get("at"));
            }
            out.append('\n').append("    ").append(item.get("detail")).append('\n');
        }
        out.append("\n■ 있어야 하는데 없는 데이터셋\n");
        if (missing.isEmpty()) {
            out.append("  없음\n");
        }
        missing.forEach(name -> out.append("  - ").append(name).append('\n'));
        return out.toString();
    }

    private static String label(Map<String, Object> item) {
        return "error".equals(item.get("level")) ? "오류" : "경고";
    }
}
