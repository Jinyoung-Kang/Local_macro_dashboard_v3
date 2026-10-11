package com.macrodash.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 감사 기록({@code audit_log}) — 로그인·비밀번호 변경·역할/등급 변경·계정 삭제. 비밀번호·토큰 값은 넣지 않습니다.
 */
@Repository
public class AuditLogStore {

    public record Entry(long id, Long userId, String action, Instant at, String detail) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditLogStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void append(Long userId, String action, Map<String, Object> detail) {
        jdbc.update("INSERT INTO audit_log (user_id, action, detail) VALUES (?, ?, ?::jsonb)",
                userId, action, mapper.writeValueAsString(detail == null ? Map.of() : detail));
    }

    /** 이보다 오래된 기록을 지우고 지운 수를 돌려줍니다. */
    public int purgeOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM audit_log WHERE at < ?", java.sql.Timestamp.from(cutoff));
    }

    public List<Entry> recent(int limit) {
        return jdbc.query("SELECT id, user_id, action, at, detail::text AS detail FROM audit_log ORDER BY at DESC, id DESC LIMIT ?",
                (rs, rowNum) -> new Entry(rs.getLong("id"), (Long) rs.getObject("user_id", Long.class),
                        rs.getString("action"), UserStore.instant(rs.getTimestamp("at")), rs.getString("detail")),
                limit);
    }
}
