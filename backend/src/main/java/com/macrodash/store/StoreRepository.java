package com.macrodash.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 저장 계층 읽기 (백엔드는 <b>읽기만</b> 합니다).
 *
 * <p>쓰기는 수집기(Python)의 몫입니다. 예외는 두 가지뿐입니다.
 * <ul>
 *   <li>수동 새로고침 기준 시각({@code refresh_requests}) — 화면 버튼이 누르는 값</li>
 *   <li>없음 — 그 외 모든 테이블은 수집기만 씁니다</li>
 * </ul>
 * 이렇게 나누면 "화면을 열었더니 수집이 시작돼 사용자가 수십 초를 기다리는"
 * 구버전의 구조적 문제가 되살아나지 않습니다.
 */
@Repository
public class StoreRepository {

    private static final Logger log = LoggerFactory.getLogger(StoreRepository.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public StoreRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ 스냅샷
    public Optional<Snapshot> readSnapshot(String name) {
        try {
            return jdbc.query(
                    "SELECT name, payload, kind, status, error, collected_at "
                            + "FROM snapshots WHERE name = ?",
                    snapshotMapper(),
                    name
            ).stream().findFirst();
        } catch (DataAccessException e) {
            log.warn("스냅샷 읽기 실패 ({}): {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    public List<Snapshot> readSnapshots(List<String> names) {
        if (names.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", names.stream().map(n -> "?").toList());
        return jdbc.query(
                "SELECT name, payload, kind, status, error, collected_at "
                        + "FROM snapshots WHERE name IN (" + placeholders + ")",
                snapshotMapper(),
                names.toArray()
        );
    }

    public List<Map<String, Object>> listSnapshotMeta() {
        return jdbc.query(
                "SELECT name, status, error, collected_at FROM snapshots ORDER BY name",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", rs.getString("name"));
                    row.put("status", rs.getString("status"));
                    row.put("error", rs.getString("error"));
                    row.put("collectedAt", toInstant(rs.getTimestamp("collected_at")));
                    return row;
                }
        );
    }

    private RowMapper<Snapshot> snapshotMapper() {
        return (ResultSet rs, int rowNum) -> new Snapshot(
                rs.getString("name"),
                parseJson(rs.getString("payload")),
                rs.getString("kind"),
                rs.getString("status"),
                rs.getString("error"),
                toInstant(rs.getTimestamp("collected_at"))
        );
    }

    private JsonNode parseJson(String raw) {
        try {
            return raw == null ? null : mapper.readTree(raw);
        } catch (Exception e) {
            log.warn("스냅샷 JSON 해석 실패: {}", e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- 시계열
    public List<TimeseriesPoint> readTimeseries(String dataset, String seriesId, LocalDate from) {
        StringBuilder sql = new StringBuilder(
                "SELECT obs_date, value FROM timeseries WHERE dataset = ? AND series_id = ?");
        List<Object> params = new ArrayList<>(List.of(dataset, seriesId));
        if (from != null) {
            sql.append(" AND obs_date >= ?");
            params.add(java.sql.Date.valueOf(from));
        }
        sql.append(" ORDER BY obs_date");

        return jdbc.query(sql.toString(), (rs, rowNum) -> new TimeseriesPoint(
                rs.getDate("obs_date").toLocalDate(),
                (Double) rs.getObject("value")
        ), params.toArray());
    }

    public long countTimeseries() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM timeseries", Long.class);
        return count == null ? 0 : count;
    }

    // -------------------------------------------------------------- 관측 레코드
    public List<JsonNode> readObservations(String dataset,
                                           LocalDate obsDate,
                                           LocalDate startDate,
                                           Map<String, String> filters) {
        StringBuilder sql = new StringBuilder(
                "SELECT obs_date, payload FROM observations WHERE dataset = ?");
        List<Object> params = new ArrayList<>(List.of(dataset));

        if (obsDate != null) {
            sql.append(" AND obs_date = ?");
            params.add(java.sql.Date.valueOf(obsDate));
        }
        if (startDate != null) {
            sql.append(" AND obs_date >= ?");
            params.add(java.sql.Date.valueOf(startDate));
        }
        if (filters != null && !filters.isEmpty()) {
            sql.append(" AND payload @> ?::jsonb");
            params.add(writeJson(filters));
        }
        sql.append(" ORDER BY obs_date DESC, entity");

        return jdbc.query(sql.toString(), (rs, rowNum) -> {
            JsonNode node = parseJson(rs.getString("payload"));
            if (node != null && node.isObject()) {
                ((tools.jackson.databind.node.ObjectNode) node)
                        .put("obsDate", rs.getDate("obs_date").toLocalDate().toString());
            }
            return node;
        }, params.toArray());
    }

    /**
     * 특정 날짜의 특정 개체만 읽습니다.
     *
     * <p>하루치 전 종목(약 3천 행)을 읽어 걸러 내지 않고, 기본키
     * (dataset, obs_date, entity) 인덱스로 필요한 행만 가져옵니다.
     *
     * @param entities 개체 키 목록 (호출하는 쪽이 형식을 검증해서 넘길 것)
     * @return entity → payload. 없는 개체는 키가 없습니다
     */
    public Map<String, JsonNode> readObservationsFor(String dataset, LocalDate obsDate,
                                                     Collection<String> entities) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (entities.isEmpty()) {
            return out;
        }
        String placeholders = String.join(",", Collections.nCopies(entities.size(), "?"));
        List<Object> params = new ArrayList<>(List.of(dataset, java.sql.Date.valueOf(obsDate)));
        params.addAll(entities);
        jdbc.query("SELECT entity, payload FROM observations WHERE dataset = ? AND obs_date = ? "
                        + "AND entity IN (" + placeholders + ")",
                (RowCallbackHandler) rs -> out.put(rs.getString("entity"), parseJson(rs.getString("payload"))),
                params.toArray());
        return out;
    }

    /**
     * 여러 개체의 최근 기록을 개체별로 묶어 읽습니다 (최신 날짜가 앞).
     *
     * <p>기본키 (dataset, obs_date, entity)의 앞 두 열로 날짜 범위를 좁힌 뒤 개체를 거릅니다.
     * 종목 수십 개 × 수십 일이라 한 번의 쿼리로 끝납니다.
     *
     * @param entities 개체 키 (호출하는 쪽이 형식을 검증해서 넘길 것)
     * @param since    이 날짜 이후 기록만 (inclusive)
     * @return entity → 기록 목록(각 payload에 {@code obsDate} 추가). 기록이 없는 개체는 키가 없습니다
     */
    public Map<String, List<JsonNode>> readObservationSeries(String dataset, Collection<String> entities,
                                                             LocalDate since) {
        Map<String, List<JsonNode>> out = new LinkedHashMap<>();
        if (entities.isEmpty()) {
            return out;
        }
        String placeholders = String.join(",", Collections.nCopies(entities.size(), "?"));
        List<Object> params = new ArrayList<>(List.of(dataset, java.sql.Date.valueOf(since)));
        params.addAll(entities);
        jdbc.query("SELECT entity, obs_date, payload FROM observations WHERE dataset = ? AND obs_date >= ? "
                        + "AND entity IN (" + placeholders + ") ORDER BY entity, obs_date DESC",
                (RowCallbackHandler) rs -> {
                    JsonNode node = parseJson(rs.getString("payload"));
                    if (node != null && node.isObject()) {
                        ((tools.jackson.databind.node.ObjectNode) node)
                                .put("obsDate", rs.getDate("obs_date").toLocalDate().toString());
                        out.computeIfAbsent(rs.getString("entity"), key -> new ArrayList<>()).add(node);
                    }
                },
                params.toArray());
        return out;
    }

    /** 그 데이터셋의 가장 최근 obs_date. 없으면 null. */
    public LocalDate latestObservationDate(String dataset) {
        java.sql.Date date = jdbc.queryForObject(
                "SELECT MAX(obs_date) FROM observations WHERE dataset = ?", java.sql.Date.class, dataset);
        return date == null ? null : date.toLocalDate();
    }

    public List<String> listObservationDates(String dataset) {
        return jdbc.query(
                "SELECT DISTINCT obs_date FROM observations WHERE dataset = ? ORDER BY obs_date DESC",
                (rs, rowNum) -> rs.getDate("obs_date").toLocalDate().toString(),
                dataset
        );
    }

    /**
     * 조건(payload 포함 관계)에 맞는 기록이 있는 날짜만, 최신 순.
     *
     * @param filters 비었으면 {@link #listObservationDates(String)}와 같습니다
     */
    public List<String> listObservationDates(String dataset, Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return listObservationDates(dataset);
        }
        return jdbc.query(
                "SELECT DISTINCT obs_date FROM observations WHERE dataset = ? AND payload @> ?::jsonb "
                        + "ORDER BY obs_date DESC",
                (rs, rowNum) -> rs.getDate("obs_date").toLocalDate().toString(),
                dataset, writeJson(filters));
    }

    public long countObservations() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM observations", Long.class);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------- 수집 실행 로그
    public Optional<Map<String, Object>> readLastRun() {
        return jdbc.queryForList("SELECT * FROM collector_runs ORDER BY id DESC LIMIT 1")
                .stream().findFirst();
    }

    /**
     * 태스크별 가장 최근 실행 1건, 태스크 이름 순.
     *
     * <p>기록에 있는 태스크 이름을 (task, id DESC) 인덱스에서 하나씩 건너뛰며 찾고, 태스크마다
     * 첫 행만 읽습니다. 예전 {@code DISTINCT ON}은 실행 기록 전체를 정렬해 1년치(43만 행)에서
     * 116ms가 걸렸습니다(지금 0.3ms). 결과는 같습니다.
     */
    public List<Map<String, Object>> readTaskSummary() {
        return jdbc.queryForList(
                "WITH RECURSIVE names AS ("
                        + " (SELECT task FROM collector_task_runs ORDER BY task LIMIT 1)"
                        + " UNION ALL"
                        + " SELECT (SELECT r.task FROM collector_task_runs r"
                        + "         WHERE r.task > n.task ORDER BY r.task LIMIT 1)"
                        + " FROM names n WHERE n.task IS NOT NULL"
                        + ") "
                        + "SELECT latest.* FROM names n "
                        + "CROSS JOIN LATERAL ("
                        + " SELECT task, speed, status, started_at, duration_ms, detail, run_id"
                        + " FROM collector_task_runs r WHERE r.task = n.task"
                        + " ORDER BY r.id DESC LIMIT 1"
                        + ") latest "
                        + "ORDER BY latest.task");
    }

    public List<Map<String, Object>> readTaskHistory(String task, int limit) {
        if (task == null || task.isBlank()) {
            return jdbc.queryForList(
                    "SELECT run_id, task, speed, status, started_at, duration_ms, detail "
                            + "FROM collector_task_runs ORDER BY id DESC LIMIT ?", limit);
        }
        return jdbc.queryForList(
                "SELECT run_id, task, speed, status, started_at, duration_ms, detail "
                        + "FROM collector_task_runs WHERE task = ? ORDER BY id DESC LIMIT ?",
                task, limit);
    }

    /**
     * 최근 실행 기록 중 정상(ok)이 아닌 것만, 최신 순.
     *
     * <p>성능 — 실행 기록은 계속 쌓입니다(하루 약 1천 행). 시각 조건만 걸면 문제가 없는 날
     * 표 전체를 거꾸로 훑게 되므로, 먼저 최근 {@code window}행(기본키 역순, 인덱스)으로
     * 범위를 자른 뒤 그 안에서 거릅니다. 비용이 표 크기와 무관하게 일정합니다.
     *
     * @param since  이 시각 이후만
     * @param window 살펴볼 최근 행 수
     */
    public List<Map<String, Object>> readRecentTaskIssues(Instant since, int window) {
        return jdbc.queryForList(
                "SELECT task, status, started_at, detail FROM ("
                        + " SELECT id, task, status, started_at, detail FROM collector_task_runs"
                        + " ORDER BY id DESC LIMIT ?) recent"
                        + " WHERE status NOT IN ('ok', 'running') AND started_at >= ?"
                        + " ORDER BY id DESC",
                window, Timestamp.from(since));
    }

    // --------------------------------------------------------- 수동 새로고침 기준
    public Instant refreshRequestedAt(String scope) {
        List<Timestamp> rows = jdbc.query(
                "SELECT requested_at FROM refresh_requests WHERE scope = ?",
                (rs, rowNum) -> rs.getTimestamp("requested_at"),
                scope);
        return rows.isEmpty() ? null : toInstant(rows.get(0));
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 직렬화 실패", e);
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** 시계열 1점. */
    public record TimeseriesPoint(LocalDate date, Double value) {
    }
}
