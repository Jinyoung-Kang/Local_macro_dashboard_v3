package com.macrodash.feature.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StatusIssuesTest {

    private static final Instant NOW = Instant.parse("2026-09-25T14:45:00Z");

    private static Map<String, Object> row(String task, String status, String at, String detail) {
        return Map.of("task", task, "status", status,
                "started_at", Timestamp.from(Instant.parse(at)), "detail", detail);
    }

    @Test
    @DisplayName("지금 실패 중인 것만 현재 목록에, 같은 사유 반복은 한 줄로 묶어 횟수를 센다")
    @SuppressWarnings("unchecked")
    void groupsAndCounts() {
        List<Map<String, Object>> latest = List.of(
                row("fsc_prices", "empty", "2026-09-25T12:37:17Z", "새 기준일 데이터가 없습니다"),
                row("fred_series", "ok", "2026-09-25T14:20:02Z", "12/12"));
        List<Map<String, Object>> history = List.of(
                row("fsc_prices", "empty", "2026-09-25T12:37:17Z", "새 기준일 데이터가 없습니다"),
                row("fsc_prices", "empty", "2026-09-25T11:37:17Z", "새 기준일 데이터가 없습니다"),
                row("sec_13f", "error", "2026-09-25T02:00:00Z", "HTTP 403"));

        Map<String, Object> out = StatusIssues.build(latest, history, List.of("국내 공식 시세 (kr.fsc_prices_meta)"),
                "partial", Set.of("fsc_prices", "fred_series", "sec_13f"), NOW);

        List<Map<String, Object>> current = (List<Map<String, Object>>) out.get("current");
        assertThat(current).hasSize(1);
        assertThat(current.get(0)).containsEntry("task", "fsc_prices").containsEntry("level", "warning");

        List<Map<String, Object>> recent = (List<Map<String, Object>>) out.get("recent");
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0)).containsEntry("count", 2)
                .containsEntry("firstAt", "2026-09-25 20:37").containsEntry("at", "2026-09-25 21:37");

        String text = (String) out.get("text");
        assertThat(text).contains("[경고] fsc_prices · 2026-09-25 21:37", "fsc_prices × 2 · 2026-09-25 20:37 ~ 2026-09-25 21:37",
                "[오류] sec_13f × 1", "국내 공식 시세 (kr.fsc_prices_meta)", "마지막 실행 partial", "생성: 2026-09-25 23:45 KST");
    }

    @Test
    @DisplayName("없앤 태스크의 옛 기록은 빼고, 사유의 비밀값은 가린다")
    @SuppressWarnings("unchecked")
    void filtersRemovedTasksAndRedacts() {
        List<Map<String, Object>> latest = List.of(
                row("seoul_apartments", "error", "2026-09-25T12:00:00Z", "x"),
                row("fred_series", "error", "2026-09-25T12:00:00Z", "url ?series_id=X&api_key=deadbeefcafe"));

        Map<String, Object> out = StatusIssues.build(latest, List.of(), List.of(), null, Set.of("fred_series"), NOW);

        List<Map<String, Object>> current = (List<Map<String, Object>>) out.get("current");
        assertThat(current).extracting(item -> item.get("task")).containsExactly("fred_series");
        assertThat((String) out.get("text")).doesNotContain("deadbeefcafe", "seoul_apartments");
    }

    @Test
    @DisplayName("문제가 없으면 각 구역에 '없음'")
    void empty() {
        String text = (String) StatusIssues.build(List.of(), List.of(), List.of(), "ok", null, NOW).get("text");
        assertThat(text).contains("■ 지금 실패 중인 태스크 (최근 결과 기준)\n  없음");
    }
}
