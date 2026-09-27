package com.macrodash.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.macrodash.analytics.Json;
import com.macrodash.collector.CollectorClient;
import com.macrodash.store.Datasets;
import com.macrodash.store.StoreReader;
import com.macrodash.store.StoreRepository;
import com.macrodash.support.InvalidRequestException;
import com.macrodash.support.Params;
import com.macrodash.support.UpstreamUnavailableException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 🗄️ 데이터 저장소 상태.
 *
 * <p>구버전 {@code collector.py --status}가 터미널에 출력하던 내용을 화면에서
 * 그대로 볼 수 있게 합니다.
 * <ul>
 *   <li>태스크별 최근 결과 — ✅ 정상 / ⚠️ 데이터 없음 / ❌ 오류 + 실패 이유</li>
 *   <li>있어야 하는데 없는 데이터셋 — 기대 목록과 대조</li>
 *   <li>실제 실행 상태 — 수집기가 죽으면 기록은 'running'에 남습니다.
 *       PID 생존 여부와 heartbeat로 검사해 '비정상 종료'로 보고합니다.</li>
 * </ul>
 */
@Service
public class DataStatusService {

    private final StoreRepository repository;
    private final StoreReader store;
    private final CollectorClient collector;

    public DataStatusService(StoreRepository repository, StoreReader store,
                             CollectorClient collector) {
        this.repository = repository;
        this.store = store;
        this.collector = collector;
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("readMode", store.readMode().name().toLowerCase());

        // 수집기가 살아 있으면 그쪽 요약을 씁니다(키 보유 여부·누락 목록을 압니다).
        Optional<JsonNode> collectorStatus = collector.status();
        if (collectorStatus.isPresent()) {
            JsonNode payload = collectorStatus.get();
            out.put("collectorReachable", true);
            out.put("keys", payload.get("keys"));
            out.put("intervals", payload.get("intervals"));
            out.put("missingDatasets", payload.get("missingDatasets"));
            out.put("lastRun", payload.get("lastRun"));
            out.put("lastRunStatus", Json.asText(payload, "lastRunStatus"));
            out.put("taskSummary", payload.get("taskSummary"));
            out.put("timeseriesRows", Json.asDouble(payload, "timeseriesRows"));
            out.put("observationRows", Json.asDouble(payload, "observationRows"));
        } else {
            // 수집기가 죽어 있어도 상태 화면은 떠야 합니다. DB만으로 채웁니다.
            out.put("collectorReachable", false);
            out.put("message",
                    "수집기에 연결하지 못했습니다. 아래 정보는 데이터베이스에서 직접 읽은 값입니다.");
            out.put("lastRun", repository.readLastRun().orElse(null));
            out.put("taskSummary", repository.readTaskSummary());
            out.put("timeseriesRows", repository.countTimeseries());
            out.put("observationRows", repository.countObservations());
        }

        out.put("snapshots", snapshotFreshness());
        out.put("radarHistoryDates", repository.listObservationDates(Datasets.OBS_RADAR));
        return out;
    }

    /** 스냅샷별 신선도. 화면이 "몇 분 전 수집"과 "오래됨"을 표시합니다. */
    private List<Map<String, Object>> snapshotFreshness() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> meta : repository.listSnapshotMeta()) {
            Map<String, Object> row = new LinkedHashMap<>(meta);
            Object collectedAt = meta.get("collectedAt");
            if (collectedAt instanceof Instant instant) {
                long age = Math.max(0,
                        java.time.Duration.between(instant, Instant.now()).getSeconds());
                row.put("ageSeconds", age);
                row.put("stale", age > Datasets.MAX_AGE_DAILY);
            }
            rows.add(row);
        }
        return rows;
    }

    /** 오류·경고 모음이 되돌아보는 기간과, 그 기간을 찾으려고 살펴볼 최근 실행 기록 수. */
    static final java.time.Duration ISSUE_LOOKBACK = java.time.Duration.ofHours(24);
    static final int ISSUE_WINDOW = 3000;

    /**
     * ⚠️ 수집 오류·경고 모음 (복사용 텍스트 포함). {@link StatusIssues} 참고.
     *
     * <p>실행 기록은 DB에서 직접 읽습니다 — 수집기가 죽어 있을 때가 가장 로그가 필요한
     * 때입니다. 수집기에 닿으면 등록된 태스크 목록과 누락 데이터셋을 더합니다.
     */
    public Map<String, Object> issues() {
        Optional<JsonNode> collectorStatus = collector.status();
        List<Map<String, Object>> latest = repository.readTaskSummary();
        List<Map<String, Object>> history =
                repository.readRecentTaskIssues(Instant.now().minus(ISSUE_LOOKBACK), ISSUE_WINDOW);

        List<String> missing = new ArrayList<>();
        String lastRunStatus = null;
        java.util.Set<String> registered = null;
        if (collectorStatus.isPresent()) {
            JsonNode payload = collectorStatus.get();
            lastRunStatus = Json.asText(payload, "lastRunStatus");
            for (JsonNode entry : Json.array(payload, "missingDatasets")) {
                missing.add(Json.asText(entry, "label") + " (" + Json.asText(entry, "name") + ")");
            }
        }
        // 없앤 태스크의 옛 기록을 거르려면 지금 등록된 목록이 필요합니다(수집기만 압니다).
        // 수집기에 닿지 않으면 거르지 않습니다 — 모르는 것을 숨기는 것보다 낫습니다.
        Optional<JsonNode> tasks = collector.tasks();
        if (tasks.isPresent()) {
            registered = new java.util.HashSet<>();
            for (JsonNode task : Json.array(tasks.get(), "tasks")) {
                registered.add(Json.asText(task, "name"));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>(
                StatusIssues.build(latest, history, missing, lastRunStatus, registered, Instant.now()));
        out.put("collectorReachable", collectorStatus.isPresent());
        out.put("lookbackHours", ISSUE_LOOKBACK.toHours());
        return out;
    }

    /** 실행 이력 한 번에 볼 수 있는 최대 행 수 (수집기 {@code /task-history}의 le=200과 같음). */
    static final int MAX_HISTORY_ROWS = 200;

    /**
     * 태스크 실행 이력.
     *
     * @param limit 1~{@value #MAX_HISTORY_ROWS}로 접습니다. 예전에는 음수가 그대로 SQL
     *              {@code LIMIT}에 들어가 500과 함께 SQL 문장이 응답에 실렸습니다.
     */
    public Map<String, Object> taskHistory(String task, int limit) {
        int rows = Params.clamp(limit, 1, MAX_HISTORY_ROWS);
        Optional<JsonNode> payload = collector.taskHistory(task, rows);
        if (payload.isPresent()) {
            return Map.of("history", payload.get().get("history"));
        }
        return Map.of("history", repository.readTaskHistory(task, rows));
    }

    /** 수동 새로고침: 기준 시각을 갱신하고, auto 모드면 fast 작업을 함께 돌립니다. */
    public Map<String, Object> refresh(boolean runFast) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("readMode", store.readMode().name().toLowerCase());

        if (store.readMode() == com.macrodash.config.AppProperties.ReadMode.STORE_ONLY) {
            // store_only의 약속은 "화면이 절대 외부를 기다리지 않는다"입니다.
            // 저장본을 다시 읽기만 하고 수집은 수집기의 몫으로 남깁니다.
            out.put("triggered", false);
            out.put("message",
                    "store_only 모드입니다. 저장본만 다시 읽었습니다 (수집은 수집기가 담당합니다).");
            return out;
        }

        Optional<JsonNode> refreshed = collector.requestRefresh();
        out.put("refreshRequested", refreshed.isPresent());

        if (runFast) {
            // 수집 시작 **전**의 실행 번호를 함께 돌려줍니다. 화면은 이 번호가
            // 바뀌고 finishedAt이 찍힐 때까지 기다렸다가 스스로 다시 읽습니다.
            // 이게 없으면 "잠시 후 새로고침하세요"라고 사람에게 떠넘기게 됩니다.
            out.put("baselineRunId", currentRunId().orElse(null));

            Optional<JsonNode> result = collector.runGroup("fast", false);
            out.put("triggered", result.isPresent());
            out.put("message", result.isPresent()
                    ? "수집기에 fast 작업을 요청했습니다. 끝나면 화면이 자동으로 갱신됩니다."
                    : "수집기에 연결하지 못했습니다. 저장본을 그대로 표시합니다.");
        } else {
            out.put("triggered", false);
            out.put("message", "다음 조회에서 저장본을 다시 확인합니다.");
        }
        return out;
    }

    /** 수집기가 기록한 마지막 실행 번호. 수집기가 죽어 있으면 비어 있습니다. */
    private Optional<Long> currentRunId() {
        return collector.status()
                .map(node -> node.path("lastRun").path("id"))
                .filter(JsonNode::isNumber)
                .map(JsonNode::asLong);
    }

    /** 수집 작업 목록 (화면에서 개별 실행 버튼을 그리기 위해). */
    public Map<String, Object> tasks() {
        Optional<JsonNode> payload = collector.tasks();
        return payload.<Map<String, Object>>map(node -> Map.of("tasks", node.get("tasks")))
                .orElseGet(() -> Map.of("tasks", List.of(), "collectorReachable", false));
    }

    /**
     * 태스크 1건의 실행을 <b>시작</b>합니다(끝날 때까지 기다리지 않습니다).
     *
     * <p>예전에는 끝날 때까지 기다렸는데, 수집기 대기 한도(90초)보다 오래 걸리는 태스크
     * (13F 등)는 실제로는 수집 중인데도 "수집기에 연결하지 못했습니다"로 표시됐습니다.
     * 이제 바로 답하고, 화면이 실행 이력을 보며 끝났는지 확인합니다.
     *
     * @return {@code accepted, task, baselineStartedAt} — 이보다 늦게 시작한 실행 기록이 생기면
     *         끝난 것입니다(수집기는 태스크가 끝날 때 기록을 남깁니다). 기록이 없었으면 null
     * @throws InvalidRequestException      수집기에 등록되지 않은 태스크
     * @throws UpstreamUnavailableException 수집기에 닿지 못함
     */
    public Map<String, Object> startTask(String taskName) {
        Optional<JsonNode> registered = collector.tasks();
        if (registered.isEmpty()) {
            throw new UpstreamUnavailableException("수집기에 연결하지 못했습니다. 수집기가 실행 중인지 확인하세요.");
        }
        boolean known = Json.array(registered.get(), "tasks").stream()
                .anyMatch(task -> taskName.equals(Json.asText(task, "name")));
        if (!known) {
            throw new InvalidRequestException("알 수 없는 태스크입니다: " + Params.echo(taskName));
        }

        String baseline = latestStartedAt(taskName);
        if (collector.runTask(taskName, false).isEmpty()) {
            throw new UpstreamUnavailableException("수집기가 실행 요청을 받지 못했습니다. 잠시 후 다시 시도하세요.");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", true);
        out.put("task", taskName);
        out.put("baselineStartedAt", baseline);
        return out;
    }

    /**
     * 그 태스크의 가장 최근 실행 기록이 시작한 시각(ISO-8601). 기록이 없으면 null.
     *
     * <p>화면이 끝났는지 확인할 때 읽는 경로({@link #taskHistory})와 <b>같은 곳</b>에서 읽습니다.
     * 다른 곳에서 읽으면 한쪽에만 있는 옛 기록을 "방금 끝난 실행"으로 착각할 수 있습니다.
     */
    private String latestStartedAt(String taskName) {
        Optional<JsonNode> fromCollector = collector.taskHistory(taskName, 1);
        if (fromCollector.isPresent()) {
            List<JsonNode> rows = Json.array(fromCollector.get(), "history");
            return rows.isEmpty() ? null : Json.asText(rows.get(0), "startedAt");
        }
        List<Map<String, Object>> rows = repository.readTaskHistory(taskName, 1);
        if (rows.isEmpty()) {
            return null;
        }
        Object started = rows.get(0).get("started_at");
        if (started instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        return started == null ? null : started.toString();
    }
}
