package com.macrodash.feature.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 수집기가 꺼졌을 때 백엔드가 DB에서 만드는 상태 응답이, 수집기가 주는 응답과 같은 모양인지 (BUG-06).
 *
 * <p>같은 응답을 두 곳이 만듭니다 — 수집기({@code collector/app/store.py}의 _serialize_run·
 * _serialize_task)와 백엔드의 DB 폴백. 예전에는 폴백이 DB 행을 그대로 내보내(started_at·ok_count)
 * 화면이 "기록 없음 · 0 / 0 · NaNs"를 그렸습니다. 한쪽만 고치면 이 테스트가 깨집니다.
 */
class StatusShapeParityTest {

    private static final Path STORE = Path.of("../collector/app/store.py");

    /** store.py에서 함수가 돌려주는 dict의 키를 순서대로 뽑습니다. */
    private static List<String> serializerKeys(String source, String function) {
        int start = source.indexOf("def " + function + "(");
        assertThat(start).as("%s를 store.py에서 찾지 못했습니다", function).isNotNegative();
        int open = source.indexOf("return {", start);
        int close = source.indexOf("\n    }", open);
        Matcher keys = Pattern.compile("\"(\\w+)\":").matcher(source.substring(open, close));
        List<String> out = new ArrayList<>();
        while (keys.find()) {
            out.add(keys.group(1));
        }
        return out;
    }

    private static Map<String, Object> runRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put("started_at", Timestamp.from(Instant.parse("2026-09-27T00:00:00Z")));
        row.put("finished_at", null);
        row.put("heartbeat_at", null);
        row.put("status", "ok");
        row.put("ok_count", 3);
        row.put("fail_count", 0);
        row.put("detail", null);
        row.put("pid", 1);
        row.put("host", "h");
        row.put("group_name", "fast");
        return row;
    }

    @Test
    @DisplayName("실행 1건(lastRun)의 키가 수집기 _serialize_run과 같다")
    void runViewMatchesCollector() throws Exception {
        assumeTrue(Files.exists(STORE), "collector/app/store.py를 찾을 수 없습니다");
        List<String> expected = serializerKeys(Files.readString(STORE), "_serialize_run");
        assertThat(expected).isNotEmpty();
        assertThat(new ArrayList<>(DataStatusService.runView(runRow()).keySet())).isEqualTo(expected);
    }

    @Test
    @DisplayName("태스크 기록 한 줄의 키가 수집기 _serialize_task와 같다")
    void taskViewMatchesCollector() throws Exception {
        assumeTrue(Files.exists(STORE), "collector/app/store.py를 찾을 수 없습니다");
        List<String> expected = serializerKeys(Files.readString(STORE), "_serialize_task");
        Map<String, Object> row = new HashMap<>();
        row.put("task", "fred_series");
        row.put("speed", "slow");
        row.put("status", "ok");
        row.put("started_at", Timestamp.from(Instant.parse("2026-09-27T00:00:00Z")));
        row.put("duration_ms", 10);
        row.put("detail", null);
        row.put("run_id", 1L);
        assertThat(expected).isNotEmpty();
        assertThat(new ArrayList<>(DataStatusService.taskView(row).keySet())).isEqualTo(expected);
        assertThat(DataStatusService.taskView(row).get("startedAt")).isEqualTo("2026-09-27T00:00:00Z");
    }

    @Test
    @DisplayName("실행 상태 판정은 수집기와 같은 규칙 — heartbeat가 30분 넘게 끊긴 running은 비정상 종료")
    void resolvedStatusFollowsCollectorRule() {
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        Map<String, Object> row = runRow();
        assertThat(DataStatusService.resolveRunStatus(null, now)).isEqualTo("none");
        assertThat(DataStatusService.resolveRunStatus(row, now)).isEqualTo("ok");

        row.put("status", "running");
        row.put("heartbeat_at", Timestamp.from(now.minusSeconds(60)));
        assertThat(DataStatusService.resolveRunStatus(row, now)).isEqualTo("running");

        row.put("heartbeat_at", Timestamp.from(now.minusSeconds(31 * 60)));
        assertThat(DataStatusService.resolveRunStatus(row, now)).isEqualTo("interrupted");

        row.put("heartbeat_at", null);   // heartbeat가 없으면 시작 시각으로 봅니다
        row.put("started_at", Timestamp.from(now.minusSeconds(2 * 3600)));
        assertThat(DataStatusService.resolveRunStatus(row, now)).isEqualTo("interrupted");
    }
}
