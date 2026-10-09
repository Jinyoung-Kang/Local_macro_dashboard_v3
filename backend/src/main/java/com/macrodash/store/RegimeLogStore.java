package com.macrodash.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Array;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 국면 판정 라이브 기록({@code regime_log}) — 백엔드가 쓰는 테이블(ADR 0008).
 *
 * <p>추가만 합니다. 고치거나 지우는 메서드를 두지 않습니다 — 판정 성과를 공개할 때 "나중에 고친
 * 판정이 아니다"를 보증하는 기록이기 때문입니다. 같은 날짜는 한 건뿐입니다(기본키 + ON CONFLICT DO NOTHING).
 */
@Repository
public class RegimeLogStore {

    /** 기록 한 건. {@code inputs}는 입력별 {value, asOf}. */
    public record Entry(LocalDate verdictDate, String code, String label, JsonNode inputs,
                        List<String> missing, String ruleVersion) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SchemaCheck schema;

    public RegimeLogStore(JdbcTemplate jdbc, ObjectMapper mapper, SchemaCheck schema) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.schema = schema;
    }

    /** 테이블이 있는가. 수집기가 아직 V3를 적용하지 않았으면 false입니다. */
    public boolean ready() {
        return schema.tableExists("regime_log");
    }

    /**
     * 그날 기록이 없을 때만 넣습니다.
     *
     * @return 이번에 넣었으면 true, 이미 있었으면 false
     */
    public boolean insertIfAbsent(Entry entry) {
        int rows = jdbc.update(
                "INSERT INTO regime_log (verdict_date, code, label, inputs, missing, rule_version) "
                        + "VALUES (?, ?, ?, ?::jsonb, ?, ?) ON CONFLICT (verdict_date) DO NOTHING",
                ps -> {
                    ps.setDate(1, Date.valueOf(entry.verdictDate()));
                    ps.setString(2, entry.code());
                    ps.setString(3, entry.label());
                    ps.setString(4, mapper.writeValueAsString(entry.inputs()));
                    ps.setArray(5, ps.getConnection().createArrayOf("text",
                            entry.missing().toArray(new String[0])));
                    ps.setString(6, entry.ruleVersion());
                });
        return rows == 1;
    }

    public boolean exists(LocalDate date) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM regime_log WHERE verdict_date = ?", Integer.class, Date.valueOf(date));
        return count != null && count > 0;
    }

    /** 최근 기록부터 {@code limit}건. */
    public List<Entry> recent(int limit) {
        return jdbc.query(
                "SELECT verdict_date, code, label, inputs, missing, rule_version FROM regime_log "
                        + "ORDER BY verdict_date DESC LIMIT ?", mapper(), limit);
    }

    /** 첫 기록 날짜(기록을 시작한 날). 없으면 비어 있음. */
    public Optional<LocalDate> firstDate() {
        Date first = jdbc.queryForObject("SELECT MIN(verdict_date) FROM regime_log", Date.class);
        return Optional.ofNullable(first).map(Date::toLocalDate);
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM regime_log", Long.class);
        return count == null ? 0 : count;
    }

    private RowMapper<Entry> mapper() {
        return (rs, rowNum) -> {
            List<String> missing = new ArrayList<>();
            Array array = rs.getArray("missing");
            if (array != null) {
                missing.addAll(Arrays.asList((String[]) array.getArray()));
            }
            JsonNode inputs = mapper.readTree(rs.getString("inputs"));
            return new Entry(rs.getDate("verdict_date").toLocalDate(), rs.getString("code"),
                    rs.getString("label"), inputs, missing, rs.getString("rule_version"));
        };
    }
}
